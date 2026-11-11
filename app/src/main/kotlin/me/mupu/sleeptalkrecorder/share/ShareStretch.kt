package me.mupu.sleeptalkrecorder.share

import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The stretch of a sleep's audio that is shared of an [event]: [selected], which the user can move
 * within [bounds], the event with [MARGIN] on either side as far as the sleep's audio reaches
 * without a gap or a taken microphone. It starts as [default], the event with the settings'
 * lead-in and tail as cut playback plays it.
 */
data class ShareStretch(val event: Stretch, val bounds: Stretch, val default: Stretch, val selected: Stretch) {

    /** How long [selected] lasts. */
    val length: Duration get() = selected.end - selected.start

    /**
     * [selected] moved to run from [start] until [end], held within [bounds] and at least
     * [MIN_LENGTH] long (or all of [bounds] when that is shorter); a start moved past the end
     * pushes the end along.
     */
    fun select(start: Duration, end: Duration): ShareStretch {
        val span = bounds.end - bounds.start
        if (span <= MIN_LENGTH) return copy(selected = bounds)

        val from = start.coerceIn(bounds.start, bounds.end - MIN_LENGTH)
        val until = end.coerceIn(from + MIN_LENGTH, bounds.end)
        return copy(selected = Stretch(from, until))
    }

    companion object {
        /** How far the stretch can be moved out beyond the event on either side. */
        val MARGIN: Duration = Duration.ofSeconds(10)

        /** The shortest stretch that is shared. */
        val MIN_LENGTH: Duration = Duration.ofMillis(500)

        /**
         * The stretch shared of [event] of the sleep [timeline], starting with the settings'
         * [leadIn] and [tail]; nothing reaches beyond the sleep's audio, over a recording's start
         * or into a stretch in which the microphone was [unavailable].
         */
        fun of(event: Stretch, timeline: SleepTimeline, leadIn: Duration, tail: Duration, unavailable: List<Stretch>): ShareStretch {
            val default = timeline.withLeadInAndTail(event, leadIn, tail, unavailable)
            val bounds = timeline.withLeadInAndTail(event, maxOf(MARGIN, leadIn), maxOf(MARGIN, tail), unavailable)
            return ShareStretch(event, bounds, default, default)
        }
    }
}

/**
 * An event's [stretch] of the audio of its sleep: the sleep's [timeline] and the stretches in which
 * its microphone was [unavailable], which a shared stretch never reaches into.
 */
data class EventAudio(val stretch: Stretch, val timeline: SleepTimeline, val unavailable: List<Stretch>)

/**
 * The stretches shared of several [events] at once, one file each: every event with one [leadIn]
 * before and one [tail] after it, from none up to [ShareStretch.MARGIN], as far as its own sleep's
 * audio reaches without a gap or a taken microphone, as cut playback plays them. The events may
 * belong to different sleeps.
 */
data class ShareStretches(val events: List<EventAudio>, val leadIn: Duration, val tail: Duration, val selected: List<Stretch>) {

    /** How long the shared stretches last together. */
    val length: Duration get() = selected.fold(Duration.ZERO) { sum, stretch -> sum + (stretch.end - stretch.start) }

    companion object {
        /** The stretches of [events] with [leadIn] and [tail], held between none and [ShareStretch.MARGIN]. */
        fun of(events: List<EventAudio>, leadIn: Duration, tail: Duration): ShareStretches {
            val before = leadIn.coerceIn(Duration.ZERO, ShareStretch.MARGIN)
            val after = tail.coerceIn(Duration.ZERO, ShareStretch.MARGIN)
            return ShareStretches(events, before, after, events.map { it.timeline.withLeadInAndTail(it.stretch, before, after, it.unavailable) })
        }
    }
}

/**
 * The name of the file shared of an event that began at [start]: its local date and clock time in
 * [zone], with the extension of [format].
 */
fun shareFileName(start: Instant, zone: ZoneId, format: ShareFormat): String {
    val extension = when (format) {
        ShareFormat.VIDEO -> "mp4"
        ShareFormat.AUDIO -> "m4a"
    }
    return "SleepTalk ${FILE_TIME.format(start.atZone(zone))}.$extension"
}

/** Dashes instead of colons, which some file systems and apps refuse in a name. */
private val FILE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss")
