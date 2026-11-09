package me.mupu.sleeptalkrecorder.time

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The stretches of the clock that decide whether a sleep is a night or a nap, by the local time it
 * was started: from [napsFrom] until [nightsFrom] a nap of that day, from [nightsFrom] until
 * [napsFrom] of the next day a night named after the evening it belongs to. The night window
 * always spans midnight, so a night started after midnight belongs to the evening before.
 *
 * Naps never start after nights; when both start at the same time, every sleep is a night.
 * The windows are a setting, and a sleep is named once, when it starts (see [dateOf]), so a change
 * of the windows renames no earlier sleep.
 */
data class SleepWindows(val napsFrom: LocalTime, val nightsFrom: LocalTime) {

    init {
        require(napsFrom <= nightsFrom) { "Naps start at $napsFrom, after nights at $nightsFrom" }
    }

    /** These windows with naps starting at [time]; null if that is after nights start. */
    fun withNapsFrom(time: LocalTime): SleepWindows? = if (time <= nightsFrom) copy(napsFrom = time) else null

    /** These windows with nights starting at [time]; null if that is before naps start. */
    fun withNightsFrom(time: LocalTime): SleepWindows? = if (time >= napsFrom) copy(nightsFrom = time) else null

    /** Whether some time of the day starts a nap; not when naps and nights start together. */
    val hasNaps: Boolean get() = napsFrom < nightsFrom

    /** The name of a sleep started at [start] as seen in [zone]. */
    fun dateOf(start: Instant, zone: ZoneId): SleepDate {
        val local = start.atZone(zone).toLocalDateTime()
        val time = local.toLocalTime()

        return when {
            time < napsFrom -> SleepDate(SleepKind.NIGHT, local.toLocalDate().minusDays(1))
            time < nightsFrom -> SleepDate(SleepKind.NAP, local.toLocalDate())
            else -> SleepDate(SleepKind.NIGHT, local.toLocalDate())
        }
    }

    /**
     * Whether a recording started at [start] continues the finished sleep named [sleep] that ended
     * at [sleepEnd], rather than starting a new one: these windows give it the same name, and it
     * starts after the sleep's end. The sleep keeps the name it got at its own start, also when the
     * windows changed since. A start before the end can only follow a clock that was set back, and
     * would put the new recording's audio before the old one's on the clock.
     */
    fun continues(sleep: SleepDate, sleepEnd: Instant, start: Instant, zone: ZoneId): Boolean =
        !start.isBefore(sleepEnd) && dateOf(start, zone) == sleep

    companion object {
        /** Naps from noon, nights from six in the evening. */
        val DEFAULT = SleepWindows(napsFrom = LocalTime.NOON, nightsFrom = LocalTime.of(18, 0))
    }
}
