package me.mupu.sleeptalkrecorder.recording

import me.mupu.sleeptalkrecorder.detection.FeatureTrackFile
import me.mupu.sleeptalkrecorder.detection.FeatureTrackRecorder
import me.mupu.sleeptalkrecorder.time.RecordingClock
import me.mupu.sleeptalkrecorder.time.SleepDate
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** What a running recording reports after every buffer. */
data class RecordingProgress(
    val sleepId: Long,
    /** The name of the sleep, given when it started. */
    val sleepDate: SleepDate,
    /** The instant of the first sample; corrected once the audio clock reports it. */
    val startedAt: Instant,
    val sampleRate: Int,
    /** Samples recorded so far, which is the position in the recording. */
    val frames: Long,
    /** RMS level of the last buffer in dBFS. */
    val levelDbfs: Double,
)

/** Why a recording ended on its own. */
enum class RecordingEnd {
    /** The wall clock reached the time the recording was to stop at the latest. */
    STOP_TIME_REACHED,

    /** The microphone could not be opened or stopped delivering audio. */
    MICROPHONE_UNAVAILABLE,

    /** Audio could not be written, for example because the storage is full. */
    STORAGE_FAILED,
}

/**
 * Records one sleep from a [source] until the calling coroutine is cancelled. The audio goes into
 * chunk files under `recordingsDirectory/<recording id>/`, next to the recording's feature track
 * ([FeatureTrackFile.FILE_NAME]), which is extracted from the same buffers; the [store] learns the new recording, the
 * audio-clock anchor of the first sample, the stretches in which the system silenced the
 * microphone and, at every flush, how many samples are safely written, so a killed process loses
 * at most the audio since the last flush.
 *
 * Cancellation is the normal way to stop. The files and the store are finished before the
 * cancellation completes. The recording also stops on its own once the wall clock reaches
 * [stopAt]; it is asked before every buffer, so a changed stop time applies at once.
 */
class RecordingSession(
    private val source: PcmSource,
    private val store: RecordingStore,
    private val recordingsDirectory: File,
    private val clocks: Clocks,
    private val stopAt: () -> Instant? = { null },
    private val chunkLength: Duration = Duration.ofMinutes(15),
    private val flushInterval: Duration = Duration.ofSeconds(10),
) {

    /**
     * Records until cancelled, calling [onProgress] after every buffer. Returns only when the
     * recording ended on its own, with the reason.
     */
    suspend fun record(onProgress: (RecordingProgress) -> Unit): RecordingEnd = source.use { mic ->
        if (!mic.start()) return RecordingEnd.MICROPHONE_UNAVAILABLE

        val provisionalStart = clocks.now()
        val ids = store.startRecording(provisionalStart, mic.sampleRate)
        val directory = File(recordingsDirectory, ids.recordingId.toString())
        val writer = ChunkedWavWriter(
            directory = directory,
            sampleRate = mic.sampleRate,
            framesPerChunk = framesOf(chunkLength, mic.sampleRate),
        )
        val features = FeatureTrackRecorder(File(directory, FeatureTrackFile.FILE_NAME))

        val silence = SilenceTracker(store, ids.recordingId)
        try {
            loop(mic, ids, writer, features, silence, provisionalStart, onProgress)
        } finally {
            withContext(NonCancellable) {
                val durableFrames = try {
                    writer.close()
                    writer.frames
                } catch (_: IOException) {
                    writer.flushedFrames
                }
                try {
                    features.close()
                } catch (_: IOException) {
                    // The audio is what counts; a short track is rebuilt from it when events are found.
                }
                silence.save(durableFrames)
                store.finish(ids.recordingId, durableFrames)
            }
        }
    }

    private suspend fun loop(
        mic: PcmSource,
        ids: NewRecording,
        writer: ChunkedWavWriter,
        features: FeatureTrackRecorder,
        silence: SilenceTracker,
        provisionalStart: Instant,
        onProgress: (RecordingProgress) -> Unit,
    ): RecordingEnd {
        val buffer = ShortArray(mic.sampleRate / BUFFERS_PER_SECOND)
        var startedAt = provisionalStart
        var anchored = false
        val flushEveryFrames = framesOf(flushInterval, mic.sampleRate)
        var nextFlushAt = flushEveryFrames

        while (true) {
            coroutineContext.ensureActive()
            val stopTime = stopAt()
            if (stopTime != null && !clocks.now().isBefore(stopTime)) return RecordingEnd.STOP_TIME_REACHED

            val read = mic.read(buffer)
            if (read < 0) return RecordingEnd.MICROPHONE_UNAVAILABLE
            if (read == 0) continue

            val bufferStart = writer.frames
            try {
                writer.write(buffer, read)
                features.push(buffer, read)
                silence.update(mic.isSilenced(), bufferStart)
                if (writer.frames >= nextFlushAt) {
                    // The track first, so that it covers every sample the store counts as saved.
                    features.flush()
                    writer.flush()
                    store.saveProgress(ids.recordingId, writer.frames)
                    silence.save(writer.frames)
                    nextFlushAt = writer.frames + flushEveryFrames
                }
            } catch (_: IOException) {
                return RecordingEnd.STORAGE_FAILED
            }

            // The audio clock reports a timestamp only once capture is under way, usually after
            // the first buffers; until then the wall-clock time of the start stands in for it.
            if (!anchored) {
                mic.timestamp()?.let { timestamp ->
                    startedAt = RecordingClock.anchoredAt(
                        sampleRate = mic.sampleRate,
                        timestampFrame = timestamp.framePosition,
                        timestampNanos = timestamp.nanoTime,
                        nowElapsedNanos = clocks.elapsedNanos(),
                        now = clocks.now(),
                    ).startedAt
                    store.anchor(ids.recordingId, startedAt)
                    anchored = true
                }
            }

            onProgress(
                RecordingProgress(
                    sleepId = ids.sleepId,
                    sleepDate = ids.sleepDate,
                    startedAt = startedAt,
                    sampleRate = mic.sampleRate,
                    frames = writer.frames,
                    levelDbfs = AudioLevel.rmsDbfs(buffer, read),
                ),
            )
        }
    }

    /**
     * Follows whether the system silences the microphone and marks each silenced stretch in the
     * store. The state is asked after every buffer and counts from that buffer's first sample: the
     * system reports it with a delay of about a buffer, as the buffer itself reaches the app.
     */
    private class SilenceTracker(private val store: RecordingStore, private val recordingId: Long) {

        /** The first sample of the stretch being silenced, or null while the microphone is heard. */
        private var since: Long? = null

        suspend fun update(silenced: Boolean, frame: Long) {
            val from = since
            when {
                silenced && from == null -> since = frame
                !silenced && from != null -> {
                    store.markMicrophoneUnavailable(recordingId, from, frame)
                    since = null
                }
            }
        }

        /** Saves the open stretch up to [frame], so that a killed process keeps it. */
        suspend fun save(frame: Long) {
            since?.takeIf { it < frame }?.let { store.markMicrophoneUnavailable(recordingId, it, frame) }
        }
    }

    private fun framesOf(duration: Duration, sampleRate: Int): Long = duration.toMillis() * sampleRate / 1_000

    private companion object {
        /** Buffers of 100 ms: short enough for a lively level meter, long enough to be cheap. */
        const val BUFFERS_PER_SECOND = 10
    }
}
