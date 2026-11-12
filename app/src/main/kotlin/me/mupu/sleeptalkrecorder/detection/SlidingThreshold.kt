package me.mupu.sleeptalkrecorder.detection

import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.HOP_MILLIS
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

/**
 * An automatic start threshold taken from the stretch of the sleep around each moment instead of
 * the whole sleep, so it can be known while the sleep is still recorded.
 *
 * The threshold at a moment is the one [EventDetector] would choose for a sleep made only of the
 * [window] around it, of which [ahead] lies after the moment. It is evaluated every [step] and
 * interpolated in between, so it changes smoothly. At the ends of the sleep the window is shifted
 * inward and keeps its full length where the sleep has it.
 *
 * The threshold of a moment is known once the recording is [ahead] plus [step] past it, plus the
 * half minute the baseline looks ahead, and is at least [window] long; with [ahead] at zero the
 * window only looks back ([LOOK_BACK]).
 */
data class SlidingThreshold(
    /** How much of the sleep each threshold is taken from. */
    val window: Duration = Duration.ofMinutes(15),
    /** How much of [window] lies after the moment: half of it centres the window. */
    val ahead: Duration = window.dividedBy(2),
    /** How often the threshold is evaluated; it is interpolated in between. */
    val step: Duration = Duration.ofSeconds(10),
) {
    /**
     * The start threshold of each hop from the residual bins of the hops ([ResidualHistogram.bin],
     * or [ResidualHistogram.NONE] for digital silence).
     */
    internal fun thresholds(bins: IntArray, params: DetectionParams): FloatArray {
        val n = bins.size
        if (n == 0) return FloatArray(0)
        val length = min(max(1, hops(window)), n)
        val after = min(hops(ahead), length)
        val stride = max(1, hops(step))

        val histogram = ResidualHistogram()
        var from = 0
        var until = 0
        val points = (n + stride - 1) / stride
        val values = FloatArray(points)
        for (point in 0 until points) {
            // Both ends only move forward, so the histogram slides over each hop once.
            val start = (point * stride + after - length).coerceIn(0, n - length)
            while (until < start + length) bins[until++].let { if (it != ResidualHistogram.NONE) histogram.add(it) }
            while (from < start) bins[from++].let { if (it != ResidualHistogram.NONE) histogram.remove(it) }
            values[point] = histogram.threshold(params)
        }

        return FloatArray(n) { i ->
            val point = i / stride
            val next = min(point + 1, points - 1)
            val share = (i - point * stride) / stride.toFloat()
            values[point] + (values[next] - values[point]) * share
        }
    }

    private fun hops(duration: Duration): Int = (duration.toMillis() / HOP_MILLIS).toInt()

    companion object {
        /**
         * The last hour, looking back only: the threshold the night scan finds events with while
         * the sleep is recorded. On the labelled test nights it comes close to one threshold per
         * night, and every moment's threshold is final ten seconds and the baseline's half minute
         * after it, from the end of the first hour on.
         */
        val LOOK_BACK = SlidingThreshold(window = Duration.ofMinutes(60), ahead = Duration.ZERO)
    }
}

/**
 * A histogram of residuals (speech-band level minus baseline) from -30 to +90 dB in steps of
 * 0.25 dB, from which the automatic start threshold is chosen.
 */
internal class ResidualHistogram {
    private val counts = IntArray(BINS)
    private var size = 0

    fun add(bin: Int) {
        counts[bin]++
        size++
    }

    fun remove(bin: Int) {
        counts[bin]--
        size--
    }

    /**
     * The median residual plus [DetectionParams.spreads] times the noise spread, which is the
     * distance from the 16th percentile to the median (one standard deviation for Gaussian noise).
     * Events lie in the upper tail and do not move it.
     */
    fun threshold(params: DetectionParams): Float {
        if (size == 0) return params.maxThresholdDb
        val median = percentile(0.5f)
        val spread = median - percentile(0.16f)
        return (median + params.spreads * spread).coerceIn(params.minThresholdDb, params.maxThresholdDb)
    }

    private fun percentile(share: Float): Float {
        val rank = (share * size).toInt()
        var below = 0
        var bin = 0
        while (bin < BINS - 1 && below + counts[bin] <= rank) below += counts[bin++]
        return bin / BINS_PER_DB + MIN_DB
    }

    companion object {
        /** The bin of a residual of [db]. */
        fun bin(db: Float): Int = ((db - MIN_DB) * BINS_PER_DB).toInt().coerceIn(0, BINS - 1)

        /** Marks a hop of digital silence, which has no residual. */
        const val NONE = -1

        private const val MIN_DB = -30f
        private const val BINS_PER_DB = 4f
        private const val BINS = 480
    }
}
