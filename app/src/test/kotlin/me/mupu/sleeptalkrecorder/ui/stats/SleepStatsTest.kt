package me.mupu.sleeptalkrecorder.ui.stats

import me.mupu.sleeptalkrecorder.data.KindColor
import me.mupu.sleeptalkrecorder.data.RecordingEntity
import me.mupu.sleeptalkrecorder.data.RecordingState
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.data.ThemeRole
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.time.SleepKind
import me.mupu.sleeptalkrecorder.time.SleepWindows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepStatsTest {

    private val zone = ZoneId.of("Europe/Berlin")

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun recording(start: String, minutes: Long) =
        RecordingEntity(sleepId = 1, startedAt = at(start), sampleRate = 16_000, frames = minutes * 60 * 16_000, state = RecordingState.FINISHED)

    private fun stored(startMinute: Double, seconds: Double, speech: Boolean = false, hidden: Boolean = false, murmur: Boolean = false): SleepEvent {
        val start = (startMinute * 60_000).toLong()
        val kind = if (speech) "speech" else if (murmur) "murmur" else "event"
        val color = KindColor.Theme(if (speech) ThemeRole.TERTIARY else ThemeRole.PRIMARY)
        return SleepEvent(0, Duration.ofMillis(start), Duration.ofMillis(start + (seconds * 1_000).toLong()), kind, null, color, hidden = hidden)
    }

    private fun summary(id: Long, start: Instant, end: Instant) =
        SleepSummary(id, start, end, SleepWindows.DEFAULT.dateOf(start, zone), 1, 0, 0, eventsFound = true)

    @Test
    fun `events are counted at the clock time they happened, also after a gap between recordings, and hidden ones not at all`() {
        // Two half-hour recordings with a half-hour gap: 22:00-22:30 and 23:00-23:30.
        val recordings = listOf(recording("2026-11-08T22:00", 30), recording("2026-11-08T23:00", 30))
        val events = listOf(stored(10.0, 2.0), stored(20.0, 9.0, speech = true, hidden = true), stored(40.0, 3.5, speech = true), stored(45.0, 1.0, murmur = true))

        val sleep = CountedSleep.of(summary(1, at("2026-11-08T22:00"), at("2026-11-08T23:30")), recordings, events)

        assertEquals(
            listOf(
                CountedEvent(at("2026-11-08T22:10"), Duration.ofSeconds(2), speech = false),
                CountedEvent(at("2026-11-08T23:10"), Duration.ofMillis(3_500), speech = true),
                CountedEvent(at("2026-11-08T23:15"), Duration.ofSeconds(1), speech = false, murmur = true),
            ),
            sleep.events,
        )
    }

    private fun event(start: String, seconds: Long = 2, speech: Boolean = false, murmur: Boolean = false) =
        CountedEvent(at(start), Duration.ofSeconds(seconds), speech, murmur)

    /** A sleep named as the default windows name it, unless it is given its [date]. */
    private fun sleep(id: Long, start: String, end: String, vararg events: CountedEvent, date: SleepDate? = null) =
        CountedSleep(id, date ?: SleepWindows.DEFAULT.dateOf(at(start), zone), at(start), at(end), events.toList())

    @Test
    fun `a sleep counts its events, its speech events and how long they talked`() {
        val sleep = sleep(
            1, "2026-11-08T22:05", "2026-11-08T23:12",
            event("2026-11-08T22:07", 3, speech = true),
            event("2026-11-08T22:30"),
            event("2026-11-08T22:31", 7, speech = true),
            event("2026-11-08T22:59"),
        )

        val stats = SleepStats.of(sleep, others = emptyList(), zone)

        assertEquals(4, stats.events)
        assertEquals(2, stats.speechEvents)
        assertEquals(Duration.ofSeconds(10), stats.talk)
        assertEquals(0.5f, stats.speechShare)
    }

    @Test
    fun `events fall into the ten minutes of the clock they started in, from the sleep's start to its end`() {
        val sleep = sleep(
            1, "2026-11-08T22:05", "2026-11-08T23:12",
            event("2026-11-08T22:07", speech = true),
            event("2026-11-08T22:51", murmur = true),
            event("2026-11-08T22:59:59", speech = true),
            event("2026-11-08T23:11"),
        )

        val bins = SleepStats.of(sleep, emptyList(), zone).tenMinutes

        assertEquals((0..7).map { at("2026-11-08T22:00").plus(Duration.ofMinutes(10L * it)) }, bins.map { it.start })
        assertEquals(listOf(1, 0, 0, 0, 0, 2, 0, 1), bins.map { it.events })
        assertEquals(listOf(1, 0, 0, 0, 0, 1, 0, 0), bins.map { it.speech })
        assertEquals(listOf(0, 0, 0, 0, 0, 1, 0, 0), bins.map { it.murmur })
    }

    @Test
    fun `the busiest ten minutes hold the most events, the earliest of equals`() {
        val sleep = sleep(
            1, "2026-11-08T22:00", "2026-11-08T23:00",
            event("2026-11-08T22:15"),
            event("2026-11-08T22:41"),
            event("2026-11-08T22:42"),
            event("2026-11-08T22:51"),
            event("2026-11-08T22:52"),
        )

        assertEquals(at("2026-11-08T22:40"), SleepStats.of(sleep, emptyList(), zone).busiest?.start)
        assertEquals(null, SleepStats.of(sleep(2, "2026-11-08T22:00", "2026-11-08T23:00"), emptyList(), zone).busiest)
    }

    @Test
    fun `ten minutes follow the local clock also where the zone is not a whole hour off UTC`() {
        val kathmandu = ZoneId.of("Asia/Kathmandu")
        val start = LocalDateTime.parse("2026-11-08T22:05").atZone(kathmandu).toInstant()
        val sleep = CountedSleep(1, SleepDate(SleepKind.NIGHT, LocalDate.of(2026, 11, 8)), start, start.plus(Duration.ofMinutes(20)), emptyList())

        val first = SleepStats.of(sleep, emptyList(), kathmandu).tenMinutes.first().start

        assertEquals(LocalDateTime.parse("2026-11-08T22:00"), LocalDateTime.ofInstant(first, kathmandu))
    }

    @Test
    fun `a sleep's talk is compared with the average of the other sleeps of its kind`() {
        val night = sleep(1, "2026-11-08T22:00", "2026-11-09T06:00", event("2026-11-08T23:00", 30, speech = true))
        val others = listOf(
            night,
            sleep(2, "2026-11-07T22:00", "2026-11-08T06:00", event("2026-11-07T23:00", 60, speech = true)),
            sleep(3, "2026-11-06T22:00", "2026-11-07T06:00", event("2026-11-06T23:00", 20, speech = true), event("2026-11-06T23:30", 40)),
            // A nap of the same day does not count for nights.
            sleep(4, "2026-11-08T14:00", "2026-11-08T15:00", event("2026-11-08T14:30", 600, speech = true)),
        )

        assertEquals(Duration.ofSeconds(40), SleepStats.of(night, others, zone).averageTalk)
        assertEquals(2, SleepStats.of(night, others, zone).compared)
        assertEquals(null, SleepStats.of(night, listOf(night), zone).averageTalk)
    }

    private val nights = listOf(
        sleep(
            3, "2026-11-08T23:30", "2026-11-09T06:10",
            event("2026-11-08T23:40", 30, speech = true),
            event("2026-11-09T00:10"),
            event("2026-11-09T00:20", 10, speech = true),
        ),
        // A nap is no night and is left out.
        sleep(2, "2026-11-08T14:00", "2026-11-08T15:00", event("2026-11-08T14:30", 600, speech = true)),
        sleep(1, "2026-11-07T22:15", "2026-11-08T05:30", event("2026-11-08T00:45"), event("2026-11-08T04:59", 20, speech = true), event("2026-11-08T05:20")),
    )

    @Test
    fun `a sleep counts as the kind it was named when it started`() {
        // Started at 13:00 while naps began at noon: a nap, also for windows that now say night.
        val nap = sleep(5, "2026-11-08T13:00", "2026-11-08T14:00", event("2026-11-08T13:30", 60, speech = true), date = SleepDate(SleepKind.NAP, LocalDate.of(2026, 11, 8)))
        val stats = NightStats.of(listOf(nap) + nights, zone, SleepWindows(LocalTime.of(14, 0), LocalTime.of(14, 0)))

        assertEquals(2, stats.nights)
        assertEquals(SleepKind.NAP, SleepStats.of(nap, nights, zone).kind)
        assertEquals(1, SleepStats.of(nap, nights, zone).compared) // the one nap among them
    }

    @Test
    fun `nights are averaged per night and naps are left out`() {
        val stats = NightStats.of(nights, zone, SleepWindows.DEFAULT)

        assertEquals(2, stats.nights)
        assertEquals(java.time.LocalDate.parse("2026-11-07"), stats.since)
        assertEquals(3.0, stats.averageEvents, 0.0)
        assertEquals(Duration.ofSeconds(30), stats.averageTalk)
        assertEquals(0.5f, stats.speechShare)
    }

    @Test
    fun `talk per night lists the latest nights, the oldest first`() {
        val stats = NightStats.of(nights, zone, SleepWindows.DEFAULT, lastNights = 1)

        assertEquals(listOf(NightTalk(3, java.time.LocalDate.parse("2026-11-08"), Duration.ofSeconds(40))), stats.talkPerNight)
    }

    @Test
    fun `events by clock hour run through the night from the earliest to the latest hour recorded`() {
        val stats = NightStats.of(nights, zone, SleepWindows.DEFAULT)

        assertEquals(listOf(22, 23, 0, 1, 2, 3, 4, 5, 6), stats.perHour.map { it.hour })
        assertEquals(listOf(0.0, 0.5, 1.5, 0.0, 0.0, 0.0, 0.5, 0.5, 0.0), stats.perHour.map { it.averageEvents })
        assertEquals(0, stats.busiestHour)
    }

    @Test
    fun `events by clock hour start with the hour nights start in when they start earlier than by default`() {
        val windows = SleepWindows(napsFrom = LocalTime.of(10, 0), nightsFrom = LocalTime.of(16, 0))
        val early = listOf(sleep(1, "2026-11-08T16:30", "2026-11-09T08:00", event("2026-11-08T16:40"), event("2026-11-09T07:10"), date = SleepDate(SleepKind.NIGHT, LocalDate.of(2026, 11, 8))))

        val stats = NightStats.of(early, zone, windows)

        assertEquals((16..23).toList() + (0..7).toList(), stats.perHour.map { it.hour })
    }

    @Test
    fun `without nights there is nothing to average`() {
        val stats = NightStats.of(listOf(nights[1]), zone, SleepWindows.DEFAULT)

        assertEquals(0, stats.nights)
        assertEquals(null, stats.since)
        assertEquals(null, stats.speechShare)
        assertEquals(null, stats.busiestHour)
        assertEquals(emptyList<HourCount>(), stats.perHour)
    }
}
