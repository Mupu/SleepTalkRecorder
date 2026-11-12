package me.mupu.sleeptalkrecorder.ui.stats

import me.mupu.sleeptalkrecorder.data.RecordingEntity
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.time.RecordingClock
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.time.SleepKind
import me.mupu.sleeptalkrecorder.time.SleepWindows
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** An event as stats count it: the instant it started, how long it lasts and whether it is speech or a murmur. */
data class CountedEvent(val start: Instant, val length: Duration, val speech: Boolean, val murmur: Boolean = false)

/**
 * A finished sleep as stats count it: its name, given when it started, the time from its first to
 * its last sample, and its events in order.
 */
data class CountedSleep(val id: Long, val date: SleepDate, val start: Instant, val end: Instant, val events: List<CountedEvent>) {

    /** How long its speech events last together. */
    val talk: Duration get() = events.filter { it.speech }.fold(Duration.ZERO) { sum, event -> sum + event.length }

    companion object {
        /**
         * The finished sleep [summary] with its [recordings] in order and its [events], whose
         * positions in the sleep's audio are mapped to the instants they were recorded: the
         * recordings lie back to back on that axis, so an event belongs to the last recording
         * that starts at or before it and happened that long after the recording's start.
         *
         * Hidden events are not counted, like everywhere else the app counts events: the user
         * marked them as not interesting.
         */
        fun of(summary: SleepSummary, recordings: List<RecordingEntity>, events: List<SleepEvent>): CountedSleep {
            val clocks = recordings.map { RecordingClock(it.startedAt, it.sampleRate) }
            val positions = ArrayList<Duration>()
            var next = Duration.ZERO
            recordings.forEachIndexed { index, recording ->
                positions += next
                next += clocks[index].durationOf(recording.frames)
            }

            fun instantAt(position: Duration): Instant {
                val recording = positions.indexOfLast { it <= position }
                return if (recording < 0) summary.startedAt else clocks[recording].startedAt + (position - positions[recording])
            }

            return CountedSleep(
                id = summary.id,
                date = summary.date,
                start = summary.startedAt,
                end = summary.endedAt ?: summary.startedAt,
                events = events.filterNot { it.hidden }.map { CountedEvent(instantAt(it.start), it.end - it.start, it.isSpeech, it.isVoice && !it.isSpeech) },
            )
        }
    }
}

/**
 * Ten minutes of the clock from [start], with the [events] that started in them, [speech] of which
 * are speech events and [murmur] murmurs.
 */
data class TenMinutes(val start: Instant, val events: Int, val speech: Int, val murmur: Int = 0)

/** The numbers of one sleep, as the stats screen shows them for "this sleep". */
data class SleepStats(
    val kind: SleepKind,
    /** The events counted. */
    val events: Int,
    /** Those of them that are speech events. */
    val speechEvents: Int,
    /** How long the speech events last together. */
    val talk: Duration,
    /** Every ten minutes of the clock from the one the sleep starts in to the one it ends in, gaps included. */
    val tenMinutes: List<TenMinutes>,
    /** How long an average other sleep of the same [kind] talked; null if there is no other. */
    val averageTalk: Duration?,
    /** How many other sleeps [averageTalk] is the average of. */
    val compared: Int,
) {
    /** The share of speech events among all events, from 0 to 1; null without events. */
    val speechShare: Float? get() = if (events == 0) null else speechEvents.toFloat() / events

    /** The ten minutes with the most events, the earliest of equals; null without events. */
    val busiest: TenMinutes? get() = tenMinutes.filter { it.events > 0 }.maxByOrNull { it.events }

    companion object {
        private val TEN_MINUTES: Duration = Duration.ofMinutes(10)

        /**
         * The numbers of [sleep], compared with [others], the counted sleeps that may include
         * [sleep] itself; ten minutes are those of the clock in [zone].
         */
        fun of(sleep: CountedSleep, others: List<CountedSleep>, zone: ZoneId): SleepStats {
            // The first ten minutes start at a time of the local clock that ends in 0, such as
            // 22:00; stepping on from there by instants keeps that across a daylight saving change.
            val local = sleep.start.atZone(zone).truncatedTo(ChronoUnit.MINUTES)
            val first = local.withMinute(local.minute / 10 * 10).toInstant()
            val count = maxOf(1L, (Duration.between(first, sleep.end).toMillis() + TEN_MINUTES.toMillis() - 1) / TEN_MINUTES.toMillis()).toInt()
            val events = IntArray(count)
            val speech = IntArray(count)
            val murmur = IntArray(count)
            for (event in sleep.events) {
                val bin = (Duration.between(first, event.start).toMillis() / TEN_MINUTES.toMillis()).toInt().coerceIn(0, count - 1)
                events[bin] += 1
                if (event.speech) speech[bin] += 1
                if (event.murmur) murmur[bin] += 1
            }

            val kind = sleep.date.kind
            val compared = others.filter { it.id != sleep.id && it.date.kind == kind }
            return SleepStats(
                kind = kind,
                events = sleep.events.size,
                speechEvents = sleep.events.count { it.speech },
                talk = sleep.talk,
                tenMinutes = List(count) { TenMinutes(first + TEN_MINUTES.multipliedBy(it.toLong()), events[it], speech[it], murmur[it]) },
                averageTalk = if (compared.isEmpty()) null else compared.fold(Duration.ZERO) { sum, other -> sum + other.talk }.dividedBy(compared.size.toLong()),
                compared = compared.size,
            )
        }
    }
}

/** How long the night of [sleepId], named after [date], talked. */
data class NightTalk(val sleepId: Long, val date: LocalDate, val talk: Duration)

/** The events per night that started in the [hour] of the clock (0 to 23), on average over all nights. */
data class HourCount(val hour: Int, val averageEvents: Double)

/** The numbers of all nights, as the stats screen shows them for "all sleeps"; naps are not counted. */
data class NightStats(
    /** How many nights are counted. */
    val nights: Int,
    /** The date of the first night; null without nights. */
    val since: LocalDate?,
    /** Events per night, on average. */
    val averageEvents: Double,
    /** How long a night talked, on average. */
    val averageTalk: Duration,
    /** The share of speech events among the events of all nights, from 0 to 1; null without events. */
    val speechShare: Float?,
    /** The talk of the latest nights, the oldest first. */
    val talkPerNight: List<NightTalk>,
    /** Every hour of the clock in the order of a night, from the earliest any night was recorded in to the latest. */
    val perHour: List<HourCount>,
) {
    /** The hour with the most events per night, the earliest in the night of equals; null without events. */
    val busiestHour: Int? get() = perHour.filter { it.averageEvents > 0 }.maxByOrNull { it.averageEvents }?.hour

    companion object {
        /** How many nights the talk per night shows by default: three weeks. */
        const val LAST_NIGHTS = 21

        /**
         * The nights among [sleeps], with the clock of [zone]; the talk per night shows the
         * [lastNights] latest. A sleep counts as the kind it was named when it started; the
         * current [windows] only order the hours of the clock.
         */
        fun of(sleeps: List<CountedSleep>, zone: ZoneId, windows: SleepWindows, lastNights: Int = LAST_NIGHTS): NightStats {
            val nights = sleeps.filter { it.date.kind == SleepKind.NIGHT }.sortedBy { it.start }
            val events = nights.sumOf { it.events.size }
            val speech = nights.sumOf { sleep -> sleep.events.count { it.speech } }
            val talk = nights.fold(Duration.ZERO) { sum, sleep -> sum + sleep.talk }

            return NightStats(
                nights = nights.size,
                since = nights.firstOrNull()?.date?.date,
                averageEvents = if (nights.isEmpty()) 0.0 else events.toDouble() / nights.size,
                averageTalk = if (nights.isEmpty()) Duration.ZERO else talk.dividedBy(nights.size.toLong()),
                speechShare = if (events == 0) null else speech.toFloat() / events,
                talkPerNight = nights.takeLast(lastNights).map { NightTalk(it.id, it.date.date, it.talk) },
                perHour = perHour(nights, zone, windows),
            )
        }

        private fun perHour(nights: List<CountedSleep>, zone: ZoneId, windows: SleepWindows): List<HourCount> {
            if (nights.isEmpty()) return emptyList()

            // Hours are counted in the order of a night, so that 23:00 comes before 00:00. The order
            // starts in the middle of the nap window, the time of day a night is least likely to
            // reach, which also leaves room for nights named under earlier windows.
            val orderStart = (windows.napsFrom.toSecondOfDay() + windows.nightsFrom.toSecondOfDay()) / 2 / 3600
            fun inNight(instant: Instant) = Math.floorMod(instant.atZone(zone).hour - orderStart, 24)
            val first = nights.minOf { inNight(it.start) }
            val last = nights.maxOf { inNight(maxOf(it.start, it.end.minusMillis(1))) }
            val counts = IntArray(24)
            for (event in nights.flatMap { it.events }) counts[inNight(event.start)] += 1

            return (first..maxOf(first, last)).map { HourCount((it + orderStart) % 24, counts[it].toDouble() / nights.size) }
        }
    }
}
