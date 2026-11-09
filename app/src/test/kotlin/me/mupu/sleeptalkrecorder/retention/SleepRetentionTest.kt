package me.mupu.sleeptalkrecorder.retention

import me.mupu.sleeptalkrecorder.data.SleepSummary
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepRetentionTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun sleep(id: Long, start: String, end: String?) = SleepSummary(
        id = id,
        startedAt = LocalDateTime.parse(start).atZone(berlin).toInstant(),
        endedAt = end?.let { LocalDateTime.parse(it).atZone(berlin).toInstant() },
        recordingCount = 1,
        eventCount = 0,
        eventMillis = 0,
        eventsFound = end != null,
    )

    private fun expiredIds(keep: Duration, today: String, vararg sleeps: SleepSummary) =
        SleepRetention(keep).expired(sleeps.toList(), LocalDate.parse(today), berlin).map { it.id }

    @Test
    fun `a sleep is kept for the given days after the day it is named after and deleted the day after`() {
        // The night of 8 to 9 November is named after 8 November.
        val night = sleep(1, "2026-11-08T23:41", "2026-11-09T07:12")

        assertEquals(emptyList<Long>(), expiredIds(Duration.ofDays(30), "2026-12-08", night))
        assertEquals(listOf(1L), expiredIds(Duration.ofDays(30), "2026-12-09", night))
    }

    @Test
    fun `a night started after midnight counts from the evening before`() {
        val night = sleep(1, "2026-11-09T01:30", "2026-11-09T07:00")

        assertEquals(emptyList<Long>(), expiredIds(Duration.ofDays(1), "2026-11-09", night))
        assertEquals(listOf(1L), expiredIds(Duration.ofDays(1), "2026-11-10", night))
    }

    @Test
    fun `a nap counts from its own day`() {
        val nap = sleep(1, "2026-11-09T14:00", "2026-11-09T15:00")

        assertEquals(emptyList<Long>(), expiredIds(Duration.ofDays(1), "2026-11-10", nap))
        assertEquals(listOf(1L), expiredIds(Duration.ofDays(1), "2026-11-11", nap))
    }

    @Test
    fun `the days are calendar days, so a daylight saving change neither adds nor removes one`() {
        // Clocks go back on 25 October 2026 in Berlin; that day has 25 hours.
        val night = sleep(1, "2026-10-24T23:00", "2026-10-25T07:00")

        assertEquals(emptyList<Long>(), expiredIds(Duration.ofDays(2), "2026-10-26", night))
        assertEquals(listOf(1L), expiredIds(Duration.ofDays(2), "2026-10-27", night))
    }

    @Test
    fun `a sleep that is still recorded is never deleted`() {
        val running = sleep(1, "2026-10-01T23:00", end = null)

        assertEquals(emptyList<Long>(), expiredIds(Duration.ofDays(1), "2026-11-09", running))
    }

    @Test
    fun `only the sleeps past their days are deleted`() {
        val old = sleep(1, "2026-11-01T23:00", "2026-11-02T07:00")
        val recent = sleep(2, "2026-11-07T23:00", "2026-11-08T07:00")

        assertEquals(listOf(1L), expiredIds(Duration.ofDays(7), "2026-11-09", old, recent))
    }
}
