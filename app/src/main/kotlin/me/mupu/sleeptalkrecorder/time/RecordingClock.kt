package me.mupu.sleeptalkrecorder.time

import java.time.Duration
import java.time.Instant

/**
 * The timeline of one recording: the audio sample clock is the time axis, and the wall clock is
 * sampled once, as the UTC instant of sample 0. Positions are therefore sample counts, and every
 * clock time follows from them; wall-clock differences, which jump with NTP or a manual time
 * change, are never used for positions.
 */
data class RecordingClock(val startedAt: Instant, val sampleRate: Int) {

    init {
        require(sampleRate > 0) { "sampleRate must be positive, was $sampleRate" }
    }

    /** The UTC instant the sample at [frame] (counted from 0) was recorded. */
    fun instantAt(frame: Long): Instant = startedAt.plus(durationOf(frame))

    /** The sample recorded at [instant], rounded down; negative before the recording started. */
    fun frameAt(instant: Instant): Long {
        val offset = Duration.between(startedAt, instant)
        // Split into whole seconds and the rest so that long offsets cannot overflow.
        return offset.seconds * sampleRate + offset.nano.toLong() * sampleRate / NANOS_PER_SECOND
    }

    /** How long [frames] samples last. */
    fun durationOf(frames: Long): Duration {
        val seconds = Math.floorDiv(frames, sampleRate.toLong())
        val rest = Math.floorMod(frames, sampleRate.toLong())
        return Duration.ofSeconds(seconds, rest * NANOS_PER_SECOND / sampleRate)
    }

    companion object {
        private const val NANOS_PER_SECOND = 1_000_000_000L

        /**
         * Anchors a recording from an audio timestamp: the audio system reports that sample
         * [timestampFrame] was captured at [timestampNanos] on the elapsed-since-boot clock, and
         * at this moment that clock reads [nowElapsedNanos] while the wall clock reads [now].
         * The result is the wall-clock instant of sample 0.
         */
        fun anchoredAt(
            sampleRate: Int,
            timestampFrame: Long,
            timestampNanos: Long,
            nowElapsedNanos: Long,
            now: Instant,
        ): RecordingClock {
            val sinceFirstFrame = RecordingClock(Instant.EPOCH, sampleRate).durationOf(timestampFrame)
            val firstFrameElapsedNanos = timestampNanos - sinceFirstFrame.toNanos()
            return RecordingClock(now.minusNanos(nowElapsedNanos - firstFrameElapsedNanos), sampleRate)
        }
    }
}
