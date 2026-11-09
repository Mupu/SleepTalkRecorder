package me.mupu.sleeptalkrecorder.time

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepDateTest {

    private val berlin = ZoneId.of("Europe/Berlin")

    private fun startedAt(localDateTime: String) =
        SleepDate.of(LocalDateTime.parse(localDateTime).atZone(berlin).toInstant(), berlin)

    @Test
    fun `a sleep started in the evening is a night named after that evening`() {
        assertEquals(SleepDate(SleepKind.NIGHT, LocalDate.of(2026, 11, 8)), startedAt("2026-11-08T23:41"))
        assertEquals(SleepDate(SleepKind.NIGHT, LocalDate.of(2026, 11, 8)), startedAt("2026-11-08T18:00"))
    }

    @Test
    fun `a sleep started after midnight and before noon belongs to the evening before`() {
        assertEquals(SleepDate(SleepKind.NIGHT, LocalDate.of(2026, 11, 8)), startedAt("2026-11-09T00:00"))
        assertEquals(SleepDate(SleepKind.NIGHT, LocalDate.of(2026, 11, 8)), startedAt("2026-11-09T02:30"))
        assertEquals(SleepDate(SleepKind.NIGHT, LocalDate.of(2026, 11, 8)), startedAt("2026-11-09T11:59"))
    }

    @Test
    fun `a night started after midnight on the first of a month belongs to the last day before`() {
        assertEquals(SleepDate(SleepKind.NIGHT, LocalDate.of(2026, 12, 31)), startedAt("2027-01-01T01:15"))
    }

    @Test
    fun `a sleep started between noon and six in the evening is a nap of that day`() {
        assertEquals(SleepDate(SleepKind.NAP, LocalDate.of(2026, 11, 9)), startedAt("2026-11-09T12:00"))
        assertEquals(SleepDate(SleepKind.NAP, LocalDate.of(2026, 11, 9)), startedAt("2026-11-09T17:59"))
    }

    @Test
    fun `the same instant is named in the zone it is shown in`() {
        val instant = LocalDateTime.parse("2026-11-09T13:00").atZone(berlin).toInstant() // 21:00 in Tokyo

        assertEquals(SleepDate(SleepKind.NAP, LocalDate.of(2026, 11, 9)), SleepDate.of(instant, berlin))
        assertEquals(SleepDate(SleepKind.NIGHT, LocalDate.of(2026, 11, 9)), SleepDate.of(instant, ZoneId.of("Asia/Tokyo")))
    }
}
