package me.mupu.sleeptalkrecorder.retention

import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.time.SleepDate
import java.time.Duration
import java.time.LocalDate

/**
 * Which sleeps automatic deletion removes when sleeps are kept for [keep].
 *
 * Age is counted in calendar days from the day a sleep is named after (see [SleepDate]), not in
 * hours from its end: a sleep kept for 30 days is still there on the 30th day after its date and
 * deleted on the 31st, whenever during that day deletion runs. So all sleeps the library lists
 * under one date go on the same day, and a daylight saving change does not shift it.
 */
class SleepRetention(keep: Duration) {

    /** Whole days; the setting only offers whole days. */
    private val keepDays: Long = keep.toDays()

    /**
     * The sleeps among [sleeps] that are past their days on [today]. A sleep still being recorded
     * is never among them.
     */
    fun expired(sleeps: List<SleepSummary>, today: LocalDate): List<SleepSummary> =
        sleeps.filter { sleep ->
            !sleep.isRunning && sleep.date.date.plusDays(keepDays) < today
        }
}
