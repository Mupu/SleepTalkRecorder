package me.mupu.sleeptalkrecorder.time

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LatestStopTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun `a night started in the evening stops at the time on the next morning`() {
        val start = Instant.parse("2026-11-08T22:41:00Z") // 23:41 in Berlin

        assertEquals(
            Instant.parse("2026-11-09T11:00:00Z"),
            latestStop(after = start, at = LocalTime.NOON, zone = berlin),
        )
    }

    @Test
    fun `a sleep started after midnight stops at the time on the same day`() {
        val start = Instant.parse("2026-11-09T01:30:00Z") // 02:30 in Berlin

        assertEquals(
            Instant.parse("2026-11-09T11:00:00Z"),
            latestStop(after = start, at = LocalTime.NOON, zone = berlin),
        )
    }

    @Test
    fun `a sleep started at or after the time stops at it on the next day`() {
        val atNoon = Instant.parse("2026-11-09T11:00:00Z")

        assertEquals(
            Instant.parse("2026-11-10T11:00:00Z"),
            latestStop(after = atNoon, at = LocalTime.NOON, zone = berlin),
        )
    }

    @Test
    fun `a time the change to summer time skips stops when the clock passes it`() {
        // On 29 March 2026 the clocks jump from 02:00 winter time (01:00 UTC) to 03:00.
        val start = Instant.parse("2026-03-28T22:00:00Z")

        assertEquals(
            Instant.parse("2026-03-29T01:00:00Z"),
            latestStop(after = start, at = LocalTime.of(2, 30), zone = berlin),
        )
    }
}
