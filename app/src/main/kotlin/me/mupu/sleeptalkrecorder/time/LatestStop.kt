package me.mupu.sleeptalkrecorder.time

import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * The first instant after [after] at which the clock in [zone] shows [at], or passes it: when a
 * recording that starts at [after] stops at the latest. A time the change to summer time skips is
 * passed at the moment of the change; a time the change back shows twice counts the first time.
 */
fun latestStop(after: Instant, at: LocalTime, zone: ZoneId): Instant {
    val startDate = after.atZone(zone).toLocalDate()
    val sameDay = instantOf(startDate.atTime(at), zone)
    return if (sameDay.isAfter(after)) sameDay else instantOf(startDate.plusDays(1).atTime(at), zone)
}

private fun instantOf(local: LocalDateTime, zone: ZoneId): Instant {
    // java.time moves a skipped time forward by the length of the gap (02:30 to 03:30); the clock
    // passes it already when it jumps (at 02:00, which then reads 03:00).
    val gap = zone.rules.getTransition(local)?.takeIf { it.isGap }
    return gap?.instant ?: local.atZone(zone).toInstant()
}
