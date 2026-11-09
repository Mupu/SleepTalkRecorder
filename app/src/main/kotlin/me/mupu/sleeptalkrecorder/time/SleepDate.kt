package me.mupu.sleeptalkrecorder.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** Whether a sleep is a night or a nap, decided by the local time it was started. */
enum class SleepKind { NIGHT, NAP }

/**
 * The calendar day a sleep is named after: for a night the evening it belongs to ("night of 8 to
 * 9 November" has the date 8 November), for a nap its own day.
 */
data class SleepDate(val kind: SleepKind, val date: LocalDate) {

    companion object {
        private val NAP_FROM: LocalTime = LocalTime.NOON
        private val NIGHT_FROM: LocalTime = LocalTime.of(18, 0)

        /**
         * Names a sleep started at [start] as seen in [zone]: started 12:00-17:59 it is a nap of
         * that day; started 18:00-11:59 it is a night, and one started after midnight belongs to
         * the evening before.
         */
        fun of(start: Instant, zone: ZoneId): SleepDate {
            val local = start.atZone(zone).toLocalDateTime()
            val time = local.toLocalTime()

            return when {
                time < NAP_FROM -> SleepDate(SleepKind.NIGHT, local.toLocalDate().minusDays(1))
                time < NIGHT_FROM -> SleepDate(SleepKind.NAP, local.toLocalDate())
                else -> SleepDate(SleepKind.NIGHT, local.toLocalDate())
            }
        }
    }
}
