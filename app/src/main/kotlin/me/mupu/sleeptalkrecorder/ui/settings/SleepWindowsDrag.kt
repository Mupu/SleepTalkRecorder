package me.mupu.sleeptalkrecorder.ui.settings

import me.mupu.sleeptalkrecorder.time.SleepWindows
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToInt

// How the two thumbs of the sleep windows bar move, in minutes of the day (0 at midnight, 1440 at
// the next one), so the bar itself only converts between minutes and pixels.

/** One of the two thumbs of the sleep windows bar. */
internal enum class WindowThumb {
    /** The sun: where naps start. */
    NAPS,

    /** The moon: where nights start. */
    NIGHTS,
}

/** The minutes in a day; the bar spans them from midnight to midnight. */
internal const val MINUTES_PER_DAY = 24 * 60

/** The step a dragged thumb snaps to. */
private const val SNAP_MINUTES = 15

/** The latest a thumb can be dragged to: a quarter to midnight, the last step within the day. */
private const val LAST_STEP = MINUTES_PER_DAY - SNAP_MINUTES

/** The minute of the day at which [thumb] sits. */
internal fun SleepWindows.minuteOf(thumb: WindowThumb): Int = when (thumb) {
    WindowThumb.NAPS -> napsFrom.minuteOfDay
    WindowThumb.NIGHTS -> nightsFrom.minuteOfDay
}

/**
 * These windows with [thumb] dragged to [minute] of the day: snapped to the nearest quarter hour
 * within the day, and stopped at the other thumb, which it can reach (no naps then) but never pass.
 * The other thumb may sit off the quarter hours after a time was picked; a thumb stops exactly there.
 */
internal fun SleepWindows.dragged(thumb: WindowThumb, minute: Float): SleepWindows {
    val snapped = ((minute / SNAP_MINUTES).roundToInt() * SNAP_MINUTES).coerceIn(0, LAST_STEP)
    return when (thumb) {
        WindowThumb.NAPS -> copy(napsFrom = timeAt(snapped.coerceAtMost(nightsFrom.minuteOfDay)))
        WindowThumb.NIGHTS -> copy(nightsFrom = timeAt(snapped.coerceAtLeast(napsFrom.minuteOfDay)))
    }
}

/**
 * The thumb a drag that starts at [minute] of the day and first moves by [delta] minutes takes: the
 * nearer one. Two thumbs at the same time are told apart by the direction, so pushed together they
 * can always be pulled apart again: the sun goes earlier, the moon later.
 */
internal fun SleepWindows.thumbFor(minute: Float, delta: Float): WindowThumb {
    val toNaps = abs(minute - napsFrom.minuteOfDay)
    val toNights = abs(minute - nightsFrom.minuteOfDay)
    return when {
        toNaps < toNights -> WindowThumb.NAPS
        toNights < toNaps -> WindowThumb.NIGHTS
        delta < 0 -> WindowThumb.NAPS
        else -> WindowThumb.NIGHTS
    }
}

private val LocalTime.minuteOfDay: Int get() = hour * 60 + minute

private fun timeAt(minuteOfDay: Int): LocalTime = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
