package me.mupu.sleeptalkrecorder.detection

import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.HOP_MILLIS
import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.unsigned
import java.time.Duration
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** The hops [start] until [end] (exclusive) of a [FeatureTrack]. */
data class HopRange(val start: Int, val end: Int) {
    /** Whether this range shares a hop with [other]. */
    fun overlaps(other: HopRange): Boolean = start < other.end && other.start < end
}

/**
 * A start threshold for the hops of [range] that replaces the automatic one, in dB above the
 * baseline: what the user set for a stretch of a sleep. The stop threshold follows it.
 */
data class ThresholdOverride(val range: HopRange, val thresholdDb: Float)

/**
 * The knobs of the [EventDetector], with the defaults of the detection design. Levels are in dB,
 * lengths of time are rounded to whole hops of 20 ms.
 */
data class DetectionParams(
    /**
     * How readily sounds become events, from 0 (least) to 1 (most sensitive). It sets how far
     * above the noise spread the automatic threshold lies and how short an event may be.
     */
    val sensitivity: Float = 0.5f,
    /** The baseline is this percentile of the speech-band level around each moment. */
    val baselinePercentile: Float = 0.2f,
    /** How much of the sleep around each moment the baseline is taken from, centred on it. */
    val baselineWindow: Duration = Duration.ofSeconds(60),
    /** The baseline never drops below this level, in dBFS. */
    val baselineFloorDb: Float = -90f,
    /** The level is averaged (in power) over this length before it is compared. */
    val smoothing: Duration = Duration.ofMillis(100),
    /** The lowest and highest automatic start threshold above the baseline. */
    val minThresholdDb: Float = 5f,
    val maxThresholdDb: Float = 25f,
    /** The stop threshold lies this share of the start threshold below it. */
    val hysteresisShare: Float = 0.4f,
    /** A sound must stay above the start threshold this long to start an event. */
    val minOnset: Duration = Duration.ofMillis(60),
    /** An event ends once the sound stays below the stop threshold this long. */
    val hangover: Duration = Duration.ofMillis(300),
    /** Events shorter than this are dropped: at sensitivity 0 and 1. */
    val minLengthAtLeastSensitive: Duration = Duration.ofMillis(200),
    val minLengthAtMostSensitive: Duration = Duration.ofMillis(80),
    /** Events closer together than this are joined into one. */
    val mergeGap: Duration = Duration.ofMillis(1500),
) {
    /** How many noise spreads above the noise median the automatic threshold lies. */
    internal val spreads: Float get() = lerp(5f, 1.8f, sensitivity)

    /** The shortest event kept. */
    internal val minLength: Duration
        get() = Duration.ofMillis(
            lerp(minLengthAtLeastSensitive.toMillis().toFloat(), minLengthAtMostSensitive.toMillis().toFloat(), sensitivity).toLong(),
        )

    private fun lerp(from: Float, to: Float, share: Float) = from + (to - from) * share.coerceIn(0f, 1f)
}

/** What the [EventDetector] found in a track, with the curves it found it on. */
class Detection(
    /** The speech-band level of each hop, smoothed, in dBFS. */
    val level: FloatArray,
    /** The baseline at each hop, in dBFS. */
    val baseline: FloatArray,
    /** How far above the baseline a sound had to be to start an event, chosen for this track. */
    val thresholdDb: Float,
    /** The overrides of [thresholdDb] the events were found with, oldest first. */
    val overrides: List<ThresholdOverride>,
    /** The events in order, not overlapping. */
    val events: List<HopRange>,
) {
    /**
     * The start threshold at [hop] with [overrides]: that of the newest override covering it, or
     * the automatic [thresholdDb].
     */
    fun thresholdAt(hop: Int, overrides: List<ThresholdOverride> = this.overrides): Float =
        overrides.lastOrNull { hop >= it.range.start && hop < it.range.end }?.thresholdDb ?: thresholdDb
}

/**
 * Finds events in a [FeatureTrack]: stretches whose speech-band level rises clearly above the
 * baseline, the background loudness at that moment.
 *
 * 1. The level is the speech band (300 to 3400 Hz), so rumble and hiss barely move it, averaged in
 *    power over [DetectionParams.smoothing].
 * 2. The baseline is a low percentile of the level over a window centred on each moment. It stays
 *    below sounds that cover less than the rest of the window and follows a changing floor (a fan
 *    switching on) within a part of the window. Digital silence is left out of it.
 * 3. The start threshold above the baseline is chosen per track from the spread of the noise: the
 *    lower half of the residual (level minus baseline) is the noise, whatever the events do.
 *    [ThresholdOverride]s replace it in the stretches the user set a sensitivity for.
 * 4. A hysteresis state machine starts an event above the start threshold and ends it after a
 *    hangover below the lower stop threshold; short events are dropped and close ones joined.
 *
 * Plain Kotlin and linear in the track's length: a night of 8 hours takes well under a second.
 */
object EventDetector {

    /** Identifies this detector and its rules in stored results; raise it when they change. */
    const val VERSION = "2"

    /**
     * Finds the events of [track], with the automatic threshold replaced by [overrides] (oldest
     * first; where they overlap, the newer one counts).
     */
    fun detect(
        track: FeatureTrack,
        params: DetectionParams = DetectionParams(),
        overrides: List<ThresholdOverride> = emptyList(),
    ): Detection {
        val speech = track.speech
        val level = smoothedLevel(speech, hops(params.smoothing))
        val baseline = baseline(speech, params)
        val threshold = automaticThreshold(speech, level, baseline, params)
        val events = events(level, baseline, threshold, overrides, HopRange(0, level.size), params)
        return Detection(level, baseline, threshold, overrides, events)
    }

    /**
     * The events [detect] would find in [range] with [overrides] in place of those of [detection],
     * whole even where they reach out of the range.
     *
     * The level, baseline and automatic threshold do not depend on the overrides, so only the
     * state machine runs again, over the range and [RESCAN_MARGIN] on either side: milliseconds
     * even for a whole night, quick enough to follow a slider.
     */
    fun rescan(
        detection: Detection,
        range: HopRange,
        overrides: List<ThresholdOverride>,
        params: DetectionParams = DetectionParams(),
    ): List<HopRange> {
        val margin = hops(RESCAN_MARGIN)
        val scanned = HopRange((range.start - margin).coerceAtLeast(0), (range.end + margin).coerceAtMost(detection.level.size))
        return events(detection.level, detection.baseline, detection.thresholdDb, overrides, scanned, params)
            .filter { it.overlaps(range) }
    }

    /** The hysteresis, minimum length and merge steps over the hops of [range]. */
    private fun events(
        level: FloatArray,
        baseline: FloatArray,
        automatic: Float,
        overrides: List<ThresholdOverride>,
        range: HopRange,
        params: DetectionParams,
    ): List<HopRange> {
        val thresholds = FloatArray(range.end - range.start) { automatic }
        for (override in overrides) {
            // Oldest first, so a newer override is written over an older one.
            val from = max(override.range.start, range.start)
            val until = min(override.range.end, range.end)
            if (from < until) thresholds.fill(override.thresholdDb, from - range.start, until - range.start)
        }
        val raw = hysteresis(level, baseline, thresholds, range, params)

        val minHops = hops(params.minLength)
        return merge(raw.filter { it.end - it.start >= minHops }, hops(params.mergeGap))
    }

    /** The speech-band level averaged in power over [hops] hops centred on each hop. */
    private fun smoothedLevel(speech: ByteArray, hops: Int): FloatArray {
        val n = speech.size
        val half = max(0, hops / 2)
        val level = FloatArray(n)
        var sum = 0.0
        var from = 0
        var until = 0
        for (i in 0 until n) {
            while (until < min(n, i + half + 1)) sum += POWER[unsigned(speech[until++])]
            while (from < i - half) sum -= POWER[unsigned(speech[from++])]
            val mean = max(sum, 0.0) / (until - from)
            level[i] = if (mean > 0) (10 * log10(mean)).toFloat() else FeatureTrack.SILENT_DB
        }
        return level
    }

    /**
     * The [DetectionParams.baselinePercentile] of the speech-band codes in a window centred on
     * each hop, from a histogram of the 256 codes that the window slides over. It is evaluated
     * every [BASELINE_STEP] hops and interpolated in between, which is invisible at the window's
     * length of a minute.
     */
    private fun baseline(speech: ByteArray, params: DetectionParams): FloatArray {
        val n = speech.size
        val half = hops(params.baselineWindow) / 2
        val histogram = IntArray(256)
        var count = 0
        var from = 0
        var until = 0

        val points = (n + BASELINE_STEP - 1) / BASELINE_STEP
        val values = FloatArray(points)
        for (point in 0 until points) {
            val centre = point * BASELINE_STEP
            while (until < min(n, centre + half)) {
                val code = unsigned(speech[until++])
                if (code > 0) {
                    histogram[code]++
                    count++
                }
            }
            while (from < centre - half) {
                val code = unsigned(speech[from++])
                if (code > 0) {
                    histogram[code]--
                    count--
                }
            }
            values[point] = if (count == 0) {
                params.baselineFloorDb
            } else {
                val rank = (params.baselinePercentile * count).toInt()
                var below = 0
                var code = 1
                while (code < 255 && below + histogram[code] <= rank) below += histogram[code++]
                max(FeatureTrack.levelDb(code.toByte()), params.baselineFloorDb)
            }
        }

        return FloatArray(n) { i ->
            val point = i / BASELINE_STEP
            val next = min(point + 1, points - 1)
            val share = (i - point * BASELINE_STEP) / BASELINE_STEP.toFloat()
            values[point] + (values[next] - values[point]) * share
        }
    }

    /**
     * The start threshold above the baseline: the median residual plus [DetectionParams.spreads]
     * times the noise spread, which is the distance from the 16th percentile to the median (one
     * standard deviation for Gaussian noise). Events lie in the upper tail and do not move it.
     */
    private fun automaticThreshold(speech: ByteArray, level: FloatArray, baseline: FloatArray, params: DetectionParams): Float {
        val histogram = IntArray(RESIDUAL_BINS)
        var count = 0
        for (i in level.indices) {
            if (speech[i].toInt() == 0) continue
            val bin = ((level[i] - baseline[i] - RESIDUAL_MIN_DB) * RESIDUAL_BINS_PER_DB).toInt()
            histogram[bin.coerceIn(0, RESIDUAL_BINS - 1)]++
            count++
        }
        if (count == 0) return params.maxThresholdDb

        fun percentile(share: Float): Float {
            val rank = (share * count).toInt()
            var below = 0
            var bin = 0
            while (bin < RESIDUAL_BINS - 1 && below + histogram[bin] <= rank) below += histogram[bin++]
            return bin / RESIDUAL_BINS_PER_DB + RESIDUAL_MIN_DB
        }

        val median = percentile(0.5f)
        val spread = median - percentile(0.16f)
        return (median + params.spreads * spread).coerceIn(params.minThresholdDb, params.maxThresholdDb)
    }

    /**
     * Starts an event once the level stayed [DetectionParams.minOnset] at or above the start
     * threshold ([thresholds] holds one for each hop of [range]) and ends it once the level stayed
     * [DetectionParams.hangover] below the stop threshold, [DetectionParams.hysteresisShare] lower.
     */
    private fun hysteresis(
        level: FloatArray,
        baseline: FloatArray,
        thresholds: FloatArray,
        range: HopRange,
        params: DetectionParams,
    ): List<HopRange> {
        val minOnset = max(1, hops(params.minOnset))
        val hangover = max(1, hops(params.hangover))
        val events = ArrayList<HopRange>()
        var inEvent = false
        var start = 0
        var above = 0
        var below = 0

        for (i in range.start until range.end) {
            val on = thresholds[i - range.start]
            val off = on * (1 - params.hysteresisShare)
            if (!inEvent) {
                if (level[i] >= baseline[i] + on) {
                    above++
                    if (above >= minOnset) {
                        // The event starts at the first hop above the threshold.
                        inEvent = true
                        start = i - above + 1
                        below = 0
                    }
                } else {
                    above = 0
                }
            } else if (level[i] < baseline[i] + off) {
                below++
                if (below >= hangover) {
                    // The event ends after its last hop above the stop threshold.
                    events += HopRange(start, i - below + 1)
                    inEvent = false
                    above = 0
                }
            } else {
                below = 0
            }
        }
        if (inEvent) events += HopRange(start, range.end - below)
        return events
    }

    private fun merge(events: List<HopRange>, gap: Int): List<HopRange> {
        val merged = ArrayList<HopRange>()
        for (event in events) {
            val last = merged.lastOrNull()
            if (last != null && event.start - last.end < gap) {
                merged[merged.lastIndex] = HopRange(last.start, event.end)
            } else {
                merged += event
            }
        }
        return merged
    }

    private fun hops(duration: Duration): Int = (duration.toMillis() / HOP_MILLIS).toInt()

    /**
     * How far beyond its range a re-scan runs the state machine, so that events reaching into the
     * range start and end as in a scan of the whole track.
     */
    private val RESCAN_MARGIN: Duration = Duration.ofSeconds(30)

    /** How often the baseline percentile is evaluated, in hops: every half second. */
    private const val BASELINE_STEP = 25

    /** The residual histogram covers -30 to +90 dB in steps of 0.25 dB. */
    private const val RESIDUAL_MIN_DB = -30f
    private const val RESIDUAL_BINS_PER_DB = 4f
    private const val RESIDUAL_BINS = 480

    /** The power of each level code; code 0 (digital silence) has none. */
    private val POWER = DoubleArray(256) { code ->
        if (code == 0) 0.0 else 10.0.pow(FeatureTrack.levelDb(code.toByte()) / 10.0)
    }
}
