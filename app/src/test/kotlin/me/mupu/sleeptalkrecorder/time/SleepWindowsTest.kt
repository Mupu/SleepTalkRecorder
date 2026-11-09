package me.mupu.sleeptalkrecorder.time

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepWindowsTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun instant(localDateTime: String) = LocalDateTime.parse(localDateTime).atZone(berlin).toInstant()

    private fun SleepWindows.startedAt(localDateTime: String) = dateOf(instant(localDateTime), berlin)

    private fun night(year: Int, month: Int, day: Int) = SleepDate(SleepKind.NIGHT, LocalDate.of(year, month, day))

    private fun nap(year: Int, month: Int, day: Int) = SleepDate(SleepKind.NAP, LocalDate.of(year, month, day))

    private val defaults = SleepWindows.DEFAULT

    @Test
    fun `by default a sleep started in the evening is a night named after that evening`() {
        assertEquals(night(2026, 11, 8), defaults.startedAt("2026-11-08T23:41"))
        assertEquals(night(2026, 11, 8), defaults.startedAt("2026-11-08T18:00"))
    }

    @Test
    fun `by default a sleep started after midnight and before noon belongs to the evening before`() {
        assertEquals(night(2026, 11, 8), defaults.startedAt("2026-11-09T00:00"))
        assertEquals(night(2026, 11, 8), defaults.startedAt("2026-11-09T02:30"))
        assertEquals(night(2026, 11, 8), defaults.startedAt("2026-11-09T11:59"))
    }

    @Test
    fun `a night started after midnight on the first of a month belongs to the last day before`() {
        assertEquals(night(2026, 12, 31), defaults.startedAt("2027-01-01T01:15"))
    }

    @Test
    fun `by default a sleep started between noon and six in the evening is a nap of that day`() {
        assertEquals(nap(2026, 11, 9), defaults.startedAt("2026-11-09T12:00"))
        assertEquals(nap(2026, 11, 9), defaults.startedAt("2026-11-09T17:59"))
    }

    @Test
    fun `the same instant is named in the zone it is shown in`() {
        val instant = instant("2026-11-09T13:00") // 21:00 in Tokyo

        assertEquals(nap(2026, 11, 9), defaults.dateOf(instant, berlin))
        assertEquals(night(2026, 11, 9), defaults.dateOf(instant, ZoneId.of("Asia/Tokyo")))
    }

    private val late = SleepWindows(napsFrom = LocalTime.of(13, 30), nightsFrom = LocalTime.of(21, 15))

    @Test
    fun `configured windows name a sleep from the night start on as a night of that evening`() {
        assertEquals(nap(2026, 11, 9), late.startedAt("2026-11-09T21:14"))
        assertEquals(night(2026, 11, 9), late.startedAt("2026-11-09T21:15"))
    }

    @Test
    fun `configured windows name a sleep from the nap start on as a nap of that day`() {
        assertEquals(night(2026, 11, 8), late.startedAt("2026-11-09T13:29"))
        assertEquals(nap(2026, 11, 9), late.startedAt("2026-11-09T13:30"))
    }

    @Test
    fun `with configured windows a sleep started after midnight still belongs to the evening before`() {
        assertEquals(night(2026, 11, 8), late.startedAt("2026-11-09T00:00"))
        assertEquals(night(2026, 11, 8), late.startedAt("2026-11-09T12:45"))
    }

    @Test
    fun `windows whose naps and nights start together have no naps`() {
        val noNaps = SleepWindows(napsFrom = LocalTime.of(18, 0), nightsFrom = LocalTime.of(18, 0))

        assertEquals(night(2026, 11, 8), noNaps.startedAt("2026-11-09T14:00"))
        assertEquals(night(2026, 11, 8), noNaps.startedAt("2026-11-09T17:59"))
        assertEquals(night(2026, 11, 9), noNaps.startedAt("2026-11-09T18:00"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `naps cannot start later than nights`() {
        SleepWindows(napsFrom = LocalTime.of(19, 0), nightsFrom = LocalTime.of(18, 0))
    }

    @Test
    fun `moving one start past the other is rejected`() {
        assertEquals(null, defaults.withNapsFrom(LocalTime.of(18, 1)))
        assertEquals(null, defaults.withNightsFrom(LocalTime.of(11, 59)))
        assertEquals(SleepWindows(LocalTime.of(18, 0), LocalTime.of(18, 0)), defaults.withNapsFrom(LocalTime.of(18, 0)))
        assertEquals(SleepWindows(LocalTime.NOON, LocalTime.of(23, 45)), defaults.withNightsFrom(LocalTime.of(23, 45)))
    }

    private fun SleepWindows.continuesAt(start: String, end: String, next: String, named: SleepDate = startedAt(start)) =
        continues(sleep = named, sleepEnd = instant(end), start = instant(next), zone = berlin)

    @Test
    fun `a recording started in the window of the last sleep continues it`() {
        assertEquals(true, defaults.continuesAt("2026-11-08T23:10", "2026-11-09T03:12", "2026-11-09T03:20"))
        assertEquals(true, defaults.continuesAt("2026-11-08T23:10", "2026-11-09T07:00", "2026-11-09T11:30"))
        assertEquals(true, defaults.continuesAt("2026-11-09T13:00", "2026-11-09T13:40", "2026-11-09T17:30"))
    }

    @Test
    fun `a recording started in another window starts a new sleep`() {
        assertEquals(false, defaults.continuesAt("2026-11-08T23:10", "2026-11-09T07:00", "2026-11-09T12:05"))
        assertEquals(false, defaults.continuesAt("2026-11-09T13:00", "2026-11-09T13:40", "2026-11-09T18:10"))
        assertEquals(false, defaults.continuesAt("2026-11-08T23:10", "2026-11-09T07:00", "2026-11-09T23:00"))
    }

    @Test
    fun `the configured windows decide whether a recording continues the last sleep`() {
        assertEquals(true, late.continuesAt("2026-11-08T23:10", "2026-11-09T07:00", "2026-11-09T13:00"))
        assertEquals(false, late.continuesAt("2026-11-08T23:10", "2026-11-09T07:00", "2026-11-09T13:30"))
    }

    @Test
    fun `the last sleep keeps the name it was given at its start when the windows changed since`() {
        // Started at 12:30 while naps began at noon, so it is a nap, although the late windows
        // now count 12:30 to the night before.
        val nap = nap(2026, 11, 9)

        assertEquals(false, late.continuesAt("2026-11-09T12:30", "2026-11-09T12:50", "2026-11-09T13:00", named = nap))
        assertEquals(true, late.continuesAt("2026-11-09T12:30", "2026-11-09T12:50", "2026-11-09T14:00", named = nap))
    }

    @Test
    fun `a recording that starts before the last sleep ended, after the clock was set back, starts a new sleep`() {
        assertEquals(false, defaults.continuesAt("2026-11-08T23:10", "2026-11-09T03:12", "2026-11-09T02:30"))
    }
}
