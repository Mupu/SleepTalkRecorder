package me.mupu.sleeptalkrecorder.ui.format

import me.mupu.sleeptalkrecorder.settings.TimeFormat
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockFormatterTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun ClockFormatter.at(localDateTime: String) =
        format(LocalDateTime.parse(localDateTime).atZone(berlin).toInstant(), berlin)

    @Test
    fun `the 24-hour format counts from 00 00 to 23 59`() {
        val clock = ClockFormatter(TimeFormat.HOURS_24)

        assertEquals("00:00", clock.at("2026-11-09T00:00"))
        assertEquals("07:38", clock.at("2026-11-09T07:38"))
        assertEquals("12:00", clock.at("2026-11-09T12:00"))
        assertEquals("23:41", clock.at("2026-11-08T23:41"))
    }

    @Test
    fun `the 12-hour format starts at 12 AM at midnight and 12 PM at noon`() {
        val clock = ClockFormatter(TimeFormat.HOURS_12)

        assertEquals("12:00 AM", clock.at("2026-11-09T00:00"))
        assertEquals("7:38 AM", clock.at("2026-11-09T07:38"))
        assertEquals("12:00 PM", clock.at("2026-11-09T12:00"))
        assertEquals("11:41 PM", clock.at("2026-11-08T23:41"))
    }

    @Test
    fun `seconds are added after the minutes in both formats`() {
        val instant = LocalDateTime.parse("2026-11-09T00:05:07").atZone(berlin).toInstant()

        assertEquals("00:05:07", ClockFormatter(TimeFormat.HOURS_24).format(instant, berlin, seconds = true))
        assertEquals("12:05:07 AM", ClockFormatter(TimeFormat.HOURS_12).format(instant, berlin, seconds = true))
    }

    @Test
    fun `an instant is shown in the zone it is formatted for`() {
        val instant = LocalDateTime.parse("2026-11-08T23:41").atZone(berlin).toInstant()

        assertEquals("07:41", ClockFormatter(TimeFormat.HOURS_24).format(instant, ZoneId.of("Asia/Tokyo")))
    }

    @Test
    fun `an hour label is a full clock time in 24 hours and the hour alone in 12 hours`() {
        val hours = listOf(0, 6, 12, 18, 24)

        assertEquals(listOf("00:00", "06:00", "12:00", "18:00", "24:00"), hours.map(ClockFormatter(TimeFormat.HOURS_24)::formatHour))
        assertEquals(listOf("12 AM", "6 AM", "12 PM", "6 PM", "12 AM"), hours.map(ClockFormatter(TimeFormat.HOURS_12)::formatHour))
    }
}
