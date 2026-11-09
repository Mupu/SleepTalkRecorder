package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.detection.SleepTrack
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

/**
 * The loudness of a sleep as the timeline draws it: the speech-band [level] the events were found
 * on and its [baseline], one value per hop of [track].
 *
 * A column of the timeline can cover thousands of hops when the whole night is shown, so the
 * loudest level of a stretch comes from precomputed maxima of 16, 256 and 4 096 hops; a column
 * then reads a few dozen values at any zoom, and a single loud hop is never lost.
 */
class SleepLoudness(
    private val track: SleepTrack,
    private val level: FloatArray,
    private val baseline: FloatArray,
) {

    private val maxima: List<FloatArray> = buildList {
        var below = level
        repeat(LEVELS) {
            val coarser = FloatArray((below.size + FAN_OUT - 1) / FAN_OUT) { bucket ->
                var loudest = Float.NEGATIVE_INFINITY
                for (i in bucket * FAN_OUT until min(below.size, (bucket + 1) * FAN_OUT)) loudest = max(loudest, below[i])
                loudest
            }
            add(coarser)
            below = coarser
        }
    }

    /** Whether the sleep has no hops, so there is no loudness to draw. */
    val isEmpty: Boolean get() = level.isEmpty()

    /** The quietest level worth drawing: a little below the baseline's quiet stretches, in dBFS. */
    val lowDb: Float = (percentile(baseline, 0.01f) - BELOW_BASELINE_DB).coerceAtLeast(MIN_DB)

    /** The loudest level drawn: a little above all but the rarest loudest hops, at least [MIN_RANGE_DB] above [lowDb]. */
    val highDb: Float = max(percentile(level, 0.9995f) + ABOVE_LOUDEST_DB, lowDb + MIN_RANGE_DB)

    /** The loudest level from [from] until [until], in dBFS; null where the sleep has no hops. */
    fun loudestIn(from: Duration, until: Duration): Float? =
        if (level.isEmpty()) null else loudestInNanos(from.toNanos(), until.toNanos())

    /**
     * [loudestIn] for positions in nanoseconds, without allocating, as the timeline asks it for
     * every column; [Float.NEGATIVE_INFINITY] where the sleep has no hops.
     */
    fun loudestInNanos(from: Long, until: Long): Float {
        if (level.isEmpty()) return Float.NEGATIVE_INFINITY
        val first = track.hopAtNanos(from)
        val last = max(first, track.hopAtNanos(until - 1))

        // The coarsest maxima of which several buckets fit into the stretch.
        var size = 1
        var values = level
        for (coarser in maxima) {
            if ((last - first + 1) < size * FAN_OUT * 4) break
            size *= FAN_OUT
            values = coarser
        }
        var loudest = Float.NEGATIVE_INFINITY
        for (bucket in first / size..last / size) loudest = max(loudest, values[bucket])
        return loudest
    }

    /** The baseline at [position], in dBFS. */
    fun baselineAt(position: Duration): Float? = if (baseline.isEmpty()) null else baseline[track.hopAt(position)]

    /** [baselineAt] for a position in nanoseconds; NaN without a baseline. */
    fun baselineAtNanos(position: Long): Float = if (baseline.isEmpty()) Float.NaN else baseline[track.hopAtNanos(position)]

    internal companion object {
        const val FAN_OUT = 16
        const val LEVELS = 3
        const val SAMPLE_STEP = 7
        const val SMALL_TRACK = 10_000
        const val MIN_DB = -110f
        const val BELOW_BASELINE_DB = 6f
        const val ABOVE_LOUDEST_DB = 3f
        const val MIN_RANGE_DB = 30f

        /** Levels per histogram bin of [percentile]: the 0.5 dB steps of the feature track's level codes. */
        const val BINS_PER_DB = 2

        /** Wider than any span of levels in dBFS; [percentile] sorts the sample of a wider one. */
        const val MAX_SPAN_DB = 1_000f

        /**
         * The value below which [share] of [values] lie: the value at that rank of the sorted
         * sample of every [SAMPLE_STEP]th value (all values of a track shorter than [SMALL_TRACK]
         * hops), which is plenty for a display range.
         *
         * A histogram of 0.5 dB bins finds the bin that holds the rank in one pass, and only the
         * few values in that bin are sorted, so a night of 1.5 million hops never sorts them all.
         */
        fun percentile(values: FloatArray, share: Float): Float {
            if (values.isEmpty()) return MIN_DB
            val step = if (values.size < SMALL_TRACK) 1 else SAMPLE_STEP
            val sampled = (values.size + step - 1) / step
            val rank = ((sampled - 1) * share).toInt()

            var lowest = Float.POSITIVE_INFINITY
            var highest = Float.NEGATIVE_INFINITY
            for (i in values.indices step step) {
                lowest = min(lowest, values[i])
                highest = max(highest, values[i])
            }
            // Values that are no levels in dB (none the app has) are sorted as they are.
            if (!lowest.isFinite() || !highest.isFinite() || highest - lowest > MAX_SPAN_DB) return values.sortedSample(step)[rank]

            fun binOf(value: Float) = ((value - lowest) * BINS_PER_DB).toInt()
            val counts = IntArray(binOf(highest) + 1)
            for (i in values.indices step step) counts[binOf(values[i])]++

            // The bin that holds the rank, and the rank within it.
            var bin = 0
            var below = 0
            while (below + counts[bin] <= rank) below += counts[bin++]
            val inBin = FloatArray(counts[bin])
            var n = 0
            for (i in values.indices step step) if (binOf(values[i]) == bin) inBin[n++] = values[i]
            inBin.sort()
            return inBin[rank - below]
        }

        private fun FloatArray.sortedSample(step: Int): FloatArray =
            FloatArray((size + step - 1) / step) { this[it * step] }.apply { sort() }
    }
}
