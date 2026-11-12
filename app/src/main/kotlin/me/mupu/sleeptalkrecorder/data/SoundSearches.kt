package me.mupu.sleeptalkrecorder.data

import android.util.Log
import me.mupu.sleeptalkrecorder.detection.CedTagger
import me.mupu.sleeptalkrecorder.detection.SearchSettings
import me.mupu.sleeptalkrecorder.detection.SlidingThreshold
import me.mupu.sleeptalkrecorder.detection.SleepSamples
import me.mupu.sleeptalkrecorder.detection.SoundSearch
import me.mupu.sleeptalkrecorder.detection.SoundTagger
import me.mupu.sleeptalkrecorder.models.SoundModel
import me.mupu.sleeptalkrecorder.playback.SleepAudio
import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import me.mupu.sleeptalkrecorder.transcription.EventSearch
import me.mupu.sleeptalkrecorder.transcription.ModelUnusable
import me.mupu.sleeptalkrecorder.transcription.SearchOutcome
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The second way of finding events on the phone: the [SoundSearch] over a sleep's feature track and
 * audio, with the CED models the user installed, stored through the [repository] part by part
 * ([SoundSearch.PART_LENGTH]), each part with the mark how far the sleep is searched, so a search
 * that Android stops goes on with the next part. A sleep is searched to its end with the [settings]
 * it began with, which are stored in the mark, so a change of the settings applies only to sleeps
 * searched afterwards. Audio that cannot be read ends the search there: the sleep keeps what was
 * found before and counts as searched, marked as partial, so it is not tried again every run.
 *
 * During the night ([searchNight]) the sleep being recorded is searched with the start threshold of
 * the last hour ([SlidingThreshold.LOOK_BACK]), each part once it is final ([SoundSearch.isSettled]),
 * under a provisional mark. After the night the whole sleep is searched again with one threshold
 * for all of it, with the settings of the night, and each part replaces the night's candidates of
 * its stretch; the windows the night heard and the scores of the candidates it stored are taken
 * over instead of running the models again ([SleepRepository.searchMemory]).
 */
class SoundSearches(
    private val repository: SleepRepository,
    /** The directory a sound model is installed in, or null while it is not installed. */
    private val installed: suspend (SoundModel) -> File?,
    private val settings: suspend () -> SearchSettings,
) : EventSearch {

    override suspend fun sleepsToSearch(): List<Long> = repository.sleepsNotSearched()

    override suspend fun search(sleepId: Long, again: Boolean, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean): SearchOutcome =
        try {
            searchParts(sleepId, again, onProgress, shouldStop)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // One sleep that fails never stops the others; it is tried again in a later run.
            Log.w(TAG, "The search of sleep $sleepId failed", error)
            SearchOutcome.FAILED
        }

    private suspend fun searchParts(sleepId: Long, again: Boolean, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean): SearchOutcome {
        val endedAt = repository.endOf(sleepId) ?: return SearchOutcome.FAILED
        val begun = if (again) null else repository.currentSearchMark(sleepId)?.takeIf { !it.done }
        val settings = begun?.settings ?: settings()
        // After the night's search the whole sleep is searched again, each part in place of the night's.
        val start = if (begun?.provisional == true) Duration.ZERO else begun?.searchedTo ?: Duration.ZERO
        val sorterModel = SoundModel.byId(settings.sortingModel) ?: SoundModel.CED_MINI
        val sorterDirectory = installed(sorterModel) ?: return SearchOutcome.NO_MODEL
        val listenerDirectory = if (settings.listening) installed(SoundModel.LISTENER) ?: return SearchOutcome.NO_MODEL else null

        onProgress(0f)
        val started = System.nanoTime()
        val track = repository.track(sleepId)
        val timeline = repository.timeline(sleepId)
        val duration = track.positionOf(track.track.size)
        val search = SoundSearch(settings)
        val memory = if (begun != null) repository.searchMemory(sleepId, track, settings) else null
        var from = start
        var found = 0
        return withContext(Dispatchers.Default) {
            val detection = search.loudness(track)
            var listener: SoundTagger? = null
            var sorter: SoundTagger? = null
            try {
                listener = listenerDirectory?.let { CedTagger.load(it, THREADS) }
                sorter = CedTagger.load(sorterDirectory, THREADS)
                SleepAudio(timeline).use { audio ->
                    val samples = AudioSamples(audio)
                    while (from < duration) {
                        val until = minOf(from + SoundSearch.PART_LENGTH, duration)
                        val share = { part: Float -> onProgress((from.toMillis() + part * (until - from).toMillis()) / duration.toMillis()) }
                        val sorted = try {
                            search.searchPart(track, detection, samples, listener, sorter, from, until, share, shouldStop, memory)
                                ?: return@withContext SearchOutcome.STOPPED
                        } catch (error: IOException) {
                            Log.w(TAG, "Sleep $sleepId: its audio cannot be read from ${from.toMinutes()} min on", error)
                            val mark = SearchMark(settings, from, done = true, partial = true)
                            repository.storeSearchPart(sleepId, endedAt, track, from, emptyList(), mark, fresh = begun == null && from == Duration.ZERO)
                            return@withContext SearchOutcome.DONE
                        }
                        val mark = SearchMark(settings, until, done = until >= duration)
                        val fresh = begun == null && from == Duration.ZERO
                        if (!repository.storeSearchPart(sleepId, endedAt, track, from, sorted, mark, fresh)) return@withContext SearchOutcome.FAILED
                        found += sorted.size
                        from = until
                    }
                }
            } catch (error: IllegalArgumentException) {
                Log.e(TAG, "Cannot load the sound models", error)
                return@withContext SearchOutcome.NO_MODEL
            } finally {
                listener?.close()
                sorter?.close()
            }
            Log.i(
                TAG,
                "Sleep $sleepId: $found candidates in ${duration.toMinutes()} min of audio (from ${start.toMinutes()} min on" +
                    "${if (begun?.provisional == true) ", after the night's search" else ""}), " +
                    "searched in ${(System.nanoTime() - started) / 1_000_000} ms with ${settings.encode()}",
            )
            SearchOutcome.DONE
        }
    }

    override suspend fun searchNight(sleepId: Long, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean): SearchOutcome =
        try {
            searchNightParts(sleepId, onProgress, shouldStop)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (unusable: ModelUnusable) {
            throw unusable
        } catch (error: Exception) {
            // The audio of a recording being written is read as far as it is stored; the next run tries again.
            Log.w(TAG, "The search during the night of sleep $sleepId failed", error)
            SearchOutcome.FAILED
        }

    private suspend fun searchNightParts(sleepId: Long, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean): SearchOutcome {
        val mark = repository.currentSearchMark(sleepId)
        // A sleep searched otherwise while it was recorded (it cannot be) is left to the search after the night.
        if (mark != null && !mark.provisional) return SearchOutcome.DONE
        var from = mark?.searchedTo ?: Duration.ZERO
        val recorded = repository.timeline(sleepId).duration
        // Nothing is read before the next part can be final.
        if (recorded < maxOf(SlidingThreshold.LOOK_BACK.window + SoundSearch.SETTLE, SoundSearch.earliestSettled(from + SoundSearch.PART_LENGTH))) {
            return SearchOutcome.DONE
        }

        val settings = mark?.settings ?: settings()
        val sorterModel = SoundModel.byId(settings.sortingModel) ?: SoundModel.CED_MINI
        val sorterDirectory = installed(sorterModel) ?: return SearchOutcome.NO_MODEL
        val listenerDirectory = if (settings.listening) installed(SoundModel.LISTENER) ?: return SearchOutcome.NO_MODEL else null

        val started = System.nanoTime()
        val track = repository.recordedTrack(sleepId)
        val timeline = repository.timeline(sleepId)
        val length = minOf(track.positionOf(track.track.size), timeline.duration)
        val search = SoundSearch(settings)
        val begunAt = from
        var found = 0
        return withContext(Dispatchers.Default) {
            val detection = search.loudness(track, SlidingThreshold.LOOK_BACK)
            var listener: SoundTagger? = null
            var sorter: SoundTagger? = null
            try {
                SleepAudio(timeline).use { audio ->
                    val samples = AudioSamples(audio)
                    while (search.isSettled(track, detection, from, from + SoundSearch.PART_LENGTH, length)) {
                        val until = from + SoundSearch.PART_LENGTH
                        if (sorter == null) {
                            try {
                                listener = listenerDirectory?.let { CedTagger.load(it, THREADS) }
                                sorter = CedTagger.load(sorterDirectory, THREADS)
                            } catch (error: Throwable) {
                                // Also an error of the native runtime: the search during the night ends for this night.
                                if (error is CancellationException) throw error
                                throw ModelUnusable("Cannot load the sound models", error)
                            }
                        }
                        onProgress(0f)
                        val heard = sortedMapOf<Int, Boolean>()
                        val sorted = search.searchPart(
                            track, detection, samples, listener, sorter!!, from, until, onProgress, shouldStop,
                            onHeard = { window, human -> heard[window] = human },
                        ) ?: return@withContext SearchOutcome.STOPPED
                        val listened = heard.takeIf { it.isNotEmpty() }?.let {
                            ListenedWindows(it.firstKey(), it.lastKey() + 1, it.filterValues { human -> human }.keys.toList(), settings.listeningThreshold)
                        }
                        val stored = repository.storeNightPart(sleepId, track, from, sorted, SearchMark(settings, until, done = false, provisional = true), listened)
                        // The night ended meanwhile; the search after it takes over.
                        if (!stored) return@withContext SearchOutcome.STOPPED
                        found += sorted.size
                        from = until
                    }
                }
            } finally {
                listener?.close()
                sorter?.close()
            }
            if (from > begunAt) {
                Log.i(
                    TAG,
                    "Sleep $sleepId during the night: $found candidates from ${begunAt.toMinutes()} to ${from.toMinutes()} min " +
                        "of ${length.toMinutes()} min recorded, searched in ${(System.nanoTime() - started) / 1_000_000} ms",
                )
            }
            SearchOutcome.DONE
        }
    }

    /** A sleep's [audio] as the search reads it: 16-bit samples scaled to -1 to 1. */
    private class AudioSamples(private val audio: SleepAudio) : SleepSamples {
        override val sampleRate: Int = audio.sampleRate
        override val length: Long = (audio.length - audio.headerBytes) / ChunkedWavWriter.BYTES_PER_FRAME

        override fun read(from: Long, count: Int): FloatArray {
            val first = from.coerceIn(0, length)
            val bytes = ByteArray((minOf(length, first + count) - first).toInt() * ChunkedWavWriter.BYTES_PER_FRAME)
            val offset = audio.headerBytes + first * ChunkedWavWriter.BYTES_PER_FRAME
            var done = 0
            while (done < bytes.size) done += audio.read(offset + done, bytes, done, bytes.size - done)
            val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            return FloatArray(bytes.size / ChunkedWavWriter.BYTES_PER_FRAME) { shorts.get(it) / PCM_SCALE }
        }
    }

    private companion object {
        const val TAG = "SoundSearch"

        /** The threads each model runs on; the clips are short, so more would mostly wait. */
        const val THREADS = 2

        /** 16-bit samples are divided by this to lie from -1 to 1, as `SleepAudio.clip` does. */
        const val PCM_SCALE = 32768f
    }
}
