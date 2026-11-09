package me.mupu.sleeptalkrecorder.time

import java.time.LocalDate

/** Whether a sleep is a night or a nap, decided by the local time it was started. */
enum class SleepKind { NIGHT, NAP }

/**
 * The name of a sleep: its [kind] and the calendar day it is named after, for a night the evening
 * it belongs to ("night of 8 to 9 November" has the date 8 November), for a nap its own day.
 * [SleepWindows.dateOf] gives it when the sleep starts, and the sleep keeps it.
 */
data class SleepDate(val kind: SleepKind, val date: LocalDate)
