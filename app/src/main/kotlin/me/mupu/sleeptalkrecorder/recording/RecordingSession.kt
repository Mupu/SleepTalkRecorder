package me.mupu.sleeptalkrecorder.recording

import me.mupu.sleeptalkrecorder.time.RecordingClock
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
    /** The instant of the first sample; corrected once the audio clock reports it. */
    val startedAt: Instant,
    val sampleRate: Int,
    /** Samples recorded so far, which is the position in the recording. */
    val frames: Long,
    /** RMS level of the last buffer in dBFS. */
    val levelDbfs: Double,
)

/** Why a recording ended on its own. */
enum class RecordingFailure {
    /** The microphone could not be opened or stopped delivering audio. */
    MICROPHONE_UNAVAILABLE,

    /** Audio could not be written, for example because the storage is full. */
    STORAGE_FAILED,
}

/**
 * Records one sleep from a [source] until the calling coroutine is cancelled. The audio goes into
 * chunk files under `recordingsDirectory/<recording id>/`; the [store] learns the new sleep, the
 * audio-clock anchor of the first sample and, at every flush, how many samples are safely
 * written, so a killed process loses at most the audio since the last flush.
 *
 * Cancellation is the normal way to stop. The files and the store are finished before the
 * cancellation completes.
 */
class RecordingSession(
    private val source: PcmSource,
    private val store: RecordingStore,
    private val recordingsDirectory: File,
    private val clocks: Clocks,
    private val chunkLength: Duration = Duration.ofMinutes(15),
    private val flushInterval: Duration = Duration.ofSeconds(10),
) {

    /**
     * Records until cancelled, calling [onProgress] after every buffer. Returns only when the
     * recording ended on its own, with the reason.
     */
    suspend fun record(onProgress: (RecordingProgress) -> Unit): RecordingFailure = source.use { mic ->
        if (!mic.start()) return RecordingFailure.MICROPHONE_UNAVAILABLE

        val provisionalStart = clocks.now()
        val ids = store.startSleep(provisionalStart, mic.sampleRate)
        val writer = ChunkedWavWriter(
            directory = File(recordingsDirectory, ids.recordingId.toString()),
            sampleRate = mic.sampleRate,
            framesPerChunk = framesOf(chunkLength, mic.sampleRate),
        )

        try {
            loop(mic, ids, writer, provisionalStart, onProgress)
        } finally {
            withContext(NonCancellable) {
                val durableFrames = try {
                    writer.close()
                    writer.frames
                } catch (_: IOException) {
                    writer.flushedFrames
                }
                store.finish(ids.recordingId, durableFrames)
            }
        }
    }

    private suspend fun loop(
        mic: PcmSource,
        ids: NewRecording,
        writer: ChunkedWavWriter,
        provisionalStart: Instant,
        onProgress: (RecordingProgress) -> Unit,
    ): RecordingFailure {
        val buffer = ShortArray(mic.sampleRate / BUFFERS_PER_SECOND)
        var startedAt = provisionalStart
        var anchored = false
        val flushEveryFrames = framesOf(flushInterval, mic.sampleRate)
        var nextFlushAt = flushEveryFrames

        while (true) {
            coroutineContext.ensureActive()

            val read = mic.read(buffer)
            if (read < 0) return RecordingFailure.MICROPHONE_UNAVAILABLE
            if (read == 0) continue

            try {
                writer.write(buffer, read)
                if (writer.frames >= nextFlushAt) {
                    writer.flush()
                    store.saveProgress(ids.recordingId, writer.frames)
                    nextFlushAt = writer.frames + flushEveryFrames
                }
            } catch (_: IOException) {
                return RecordingFailure.STORAGE_FAILED
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
                    startedAt = startedAt,
                    sampleRate = mic.sampleRate,
                    frames = writer.frames,
                    levelDbfs = AudioLevel.rmsDbfs(buffer, read),
                ),
            )
        }
    }

    private fun framesOf(duration: Duration, sampleRate: Int): Long = duration.toMillis() * sampleRate / 1_000

    private companion object {
        /** Buffers of 100 ms: short enough for a lively level meter, long enough to be cheap. */
        const val BUFFERS_PER_SECOND = 10
    }
}
