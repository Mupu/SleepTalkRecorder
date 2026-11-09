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

    /** The quietest level worth drawing: a little below the baseline's quiet stretches, in dBFS. */
    val lowDb: Float = (percentile(baseline, 0.01f) - BELOW_BASELINE_DB).coerceAtLeast(MIN_DB)

    /** The loudest level drawn: a little above all but the rarest loudest hops, at least [MIN_RANGE_DB] above [lowDb]. */
    val highDb: Float = max(percentile(level, 0.9995f) + ABOVE_LOUDEST_DB, lowDb + MIN_RANGE_DB)

    /** The loudest level from [from] until [until], in dBFS; null where the sleep has no hops. */
    fun loudestIn(from: Duration, until: Duration): Float? {
        if (level.isEmpty()) return null
        val first = track.hopAt(from)
        val last = max(first, track.hopAt(until - Duration.ofNanos(1)))

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

    private fun percentile(values: FloatArray, share: Float): Float {
        if (values.isEmpty()) return MIN_DB
        // Every few hops are plenty for a display range and keep this fast for a long night.
        val sample = values.filterIndexed { i, _ -> i % SAMPLE_STEP == 0 || values.size < 10_000 }.sorted()
        return sample[((sample.size - 1) * share).toInt()]
    }

    private companion object {
        const val FAN_OUT = 16
        const val LEVELS = 3
        const val SAMPLE_STEP = 7
        const val MIN_DB = -110f
        const val BELOW_BASELINE_DB = 6f
        const val ABOVE_LOUDEST_DB = 3f
        const val MIN_RANGE_DB = 30f
    }
}
