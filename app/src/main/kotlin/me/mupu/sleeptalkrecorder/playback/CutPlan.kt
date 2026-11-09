package me.mupu.sleeptalkrecorder.playback

import java.time.Duration

/**
 * What *cut playback* plays of a sleep: the [stretches] around its events, sorted and without
 * overlaps; everything between them is skipped.
 */
class CutPlan private constructor(
    /** The stretches that are played, sorted, none overlapping another. */
    val stretches: List<Stretch>,
) {

    /**
     * Where cut playback goes on from [position]: [position] itself while a stretch plays it,
     * otherwise the start of the next stretch; null after the last one.
     */
    fun continueFrom(position: Duration): Duration? {
        val next = stretches.firstOrNull { position < it.end } ?: return null
        return maxOf(position, next.start)
    }

    /** The gain of every sample of a sleep recorded with [sampleRate] samples per second. */
    fun envelope(sampleRate: Int): CutEnvelope {
        fun frameOf(position: Duration) = position.toNanos() * sampleRate / NANOS_PER_SECOND
        return CutEnvelope(
            starts = LongArray(stretches.size) { frameOf(stretches[it].start) },
            ends = LongArray(stretches.size) { frameOf(stretches[it].end) },
            fadeFrames = fadeFrames(sampleRate),
        )
    }

    companion object {
        /**
         * How long the sound fades in at the start of a stretch and out at its end. A jump is
         * meant to be heard, so the fade is short; it only keeps the cut from clicking.
         */
        val FADE: Duration = Duration.ofMillis(15)

        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** The samples [FADE] lasts at [sampleRate] samples per second, at least one. */
        fun fadeFrames(sampleRate: Int): Long = (FADE.toNanos() * sampleRate / NANOS_PER_SECOND).coerceAtLeast(1)

        /**
         * The plan that plays [stretches], each an event with its lead-in and tail, on the playing
         * axis. Overlapping stretches play as one, so there is no jump between their events.
         * Stretches that only touch stay apart and fade out and in where they meet, which is where
         * lead-in and tail were cut back at the start of a recording: the audio on both sides was
         * recorded at different times, so it would not join without a click.
         */
        fun of(stretches: List<Stretch>): CutPlan {
            val merged = ArrayList<Stretch>()
            for (stretch in stretches.sortedBy { it.start }) {
                if (stretch.end <= stretch.start) continue

                val last = merged.lastOrNull()
                if (last != null && stretch.start < last.end) {
                    merged[merged.lastIndex] = last.copy(end = maxOf(last.end, stretch.end))
                } else {
                    merged += stretch
                }
            }
            return CutPlan(merged)
        }
    }
}

/**
 * The gain of each sample in cut playback, by its frame (sample index) in the sleep's audio: 1
 * inside a stretch, 0 between stretches, and a linear ramp over [fadeFrames] at both ends of each
 * stretch, so a jump never cuts into a waveform. [starts] and [ends] are the stretches in frames.
 */
class CutEnvelope(private val starts: LongArray, private val ends: LongArray, private val fadeFrames: Long) {

    /** The gain of the sample at [frame], from 0 to 1. */
    fun gainAt(frame: Long): Float {
        // The last stretch that starts at or before the frame.
        var low = 0
        var high = starts.size - 1
        var found = -1
        while (low <= high) {
            val middle = (low + high) ushr 1
            if (starts[middle] <= frame) {
                found = middle
                low = middle + 1
            } else {
                high = middle - 1
            }
        }
        if (found < 0 || frame >= ends[found]) return 0f

        val fromEdge = minOf(frame - starts[found], ends[found] - frame)
        return if (fromEdge >= fadeFrames) 1f else fromEdge.toFloat() / fadeFrames
    }
}
