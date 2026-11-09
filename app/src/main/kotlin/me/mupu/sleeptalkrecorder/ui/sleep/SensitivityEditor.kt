package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.detection.Detection
import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.detection.HopRange
import me.mupu.sleeptalkrecorder.detection.SensitivityOverride
import me.mupu.sleeptalkrecorder.detection.SleepTrack
import me.mupu.sleeptalkrecorder.detection.replacedBy
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

/** What the sensitivity sheet would set for its stretch. */
sealed interface SensitivitySetting {
    /** Nothing yet: the stretch keeps the settings it has. */
    data object Unchanged : SensitivitySetting

    /** A start threshold of [db] above the baseline for the whole stretch. */
    data class Threshold(val db: Float) : SensitivitySetting

    /** The user's settings within the stretch are taken back. */
    data object Inherited : SensitivitySetting
}

/**
 * The sensitivity sheet's view of a sleep: the [window] of its audio it sets the sensitivity for
 * (around an event, or the whole sleep), with the user's [existing] settings (oldest first) and
 * the [detection] of the sleep's [track], whose level, baseline and automatic threshold do not
 * depend on them. Every preview re-scans only the window ([EventDetector.rescan]).
 *
 * [anchor] is the event's start, where the slider reads the threshold in force; null for the whole
 * sleep, whose slider starts at its own setting or the automatic threshold.
 */
class SensitivityEditor(
    private val track: SleepTrack,
    private val detection: Detection,
    private val existing: List<SensitivityOverride>,
    val window: Stretch,
    private val anchor: Duration?,
) {
    /** The hops of [window]. */
    val hops: HopRange = track.hopsOf(window.start, window.end)

    /** The settings that stay when the window gets its own; the window's own ones go. */
    private val remaining = existing - existing.replacedBy(window.start, window.end).toSet()

    /** The threshold the slider starts at: what the newest setting of the whole window or the one in force at [anchor] sets. */
    val currentDb: Float = existing.lastOrNull { it.start <= window.start && it.end >= window.end }?.thresholdDb
        ?: anchor?.let { detection.thresholdAt(track.hopAt(it), track.thresholdOverrides(existing)) }
        ?: detection.thresholdDb

    /** The threshold the slider returns to on a reset: the one in force without the window's own settings. */
    val inheritedDb: Float = remaining.lastOrNull { it.start <= window.start && it.end >= window.end }?.thresholdDb
        ?: anchor?.let { detection.thresholdAt(track.hopAt(it), track.thresholdOverrides(remaining)) }
        ?: detection.thresholdDb

    /** The user's settings of the sleep with [setting] made, oldest first. */
    fun overrides(setting: SensitivitySetting): List<SensitivityOverride> = when (setting) {
        SensitivitySetting.Unchanged -> existing
        is SensitivitySetting.Threshold -> remaining + SensitivityOverride(window.start, window.end, setting.db)
        SensitivitySetting.Inherited -> remaining
    }

    /** The events that lie in [window] with [setting] made, whole where they reach out of it. */
    fun events(setting: SensitivitySetting): List<HopRange> =
        EventDetector.rescan(detection, hops, track.thresholdOverrides(overrides(setting)))

    /** The window as the sheet draws it with [setting] made, in [columns] columns. */
    fun chart(setting: SensitivitySetting, columns: Int): SensitivityChart {
        val events = events(setting)
        val overrides = track.thresholdOverrides(overrides(setting))
        val count = max(1, min(columns, hops.end - hops.start))
        val level = FloatArray(count)
        val baseline = FloatArray(count)
        val threshold = FloatArray(count)
        val inEvent = BooleanArray(count)

        var nextEvent = 0
        for (column in 0 until count) {
            val from = hops.start + (hops.end - hops.start).toLong().times(column).div(count).toInt()
            val until = max(from + 1, hops.start + (hops.end - hops.start).toLong().times(column + 1).div(count).toInt())
            var loudest = Float.NEGATIVE_INFINITY
            for (hop in from until min(until, detection.level.size)) loudest = max(loudest, detection.level[hop])
            val middle = min((from + until) / 2, detection.level.size - 1)
            level[column] = loudest
            baseline[column] = detection.baseline.getOrElse(middle) { 0f }
            threshold[column] = detection.thresholdAt(middle, overrides)
            while (nextEvent < events.size && events[nextEvent].end <= from) nextEvent++
            inEvent[column] = events.getOrNull(nextEvent)?.let { it.start < until } == true
        }

        val quietest = baseline.minOrNull() ?: 0f
        val lowDb = quietest - BELOW_BASELINE_DB
        val highDb = max((level.maxOrNull() ?: quietest) + ABOVE_LOUDEST_DB, lowDb + MIN_RANGE_DB)
        return SensitivityChart(level, baseline, threshold, inEvent, lowDb, highDb, events.size)
    }

    /** Where [position] lies across the window, from 0 at its start to 1 at its end. */
    fun shareOf(position: Duration): Float {
        val span = (window.end - window.start).toMillis().coerceAtLeast(1)
        return ((position - window.start).toMillis().toFloat() / span)
    }

    /** The position at [share] of the window. */
    fun positionAt(share: Float): Duration =
        window.start + Duration.ofMillis(((window.end - window.start).toMillis() * share).toLong())

    private companion object {
        const val BELOW_BASELINE_DB = 6f
        const val ABOVE_LOUDEST_DB = 3f
        const val MIN_RANGE_DB = 30f
    }
}

/**
 * The window of the sensitivity sheet in columns, each with its loudest smoothed [level], the
 * [baseline] and the start [threshold] above it (all dBFS or dB), and whether an event found with
 * the setting lies in it; [lowDb] to [highDb] is the range worth drawing. [found] counts the events.
 */
class SensitivityChart(
    val level: FloatArray,
    val baseline: FloatArray,
    val threshold: FloatArray,
    val inEvent: BooleanArray,
    val lowDb: Float,
    val highDb: Float,
    val found: Int,
)
