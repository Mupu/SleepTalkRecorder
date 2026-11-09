package me.mupu.sleeptalkrecorder.ui.sleep

import java.time.Duration

/**
 * The stretch from [start] to [end] of a sleep of length [total] that the zoomable timeline shows.
 * Every operation returns a window inside the sleep, at least [MIN_SPAN] long (or the whole sleep
 * if it is shorter).
 */
data class TimelineWindow(val start: Duration, val end: Duration, val total: Duration) {

    /** How much of the sleep the window shows. */
    val span: Duration get() = end - start

    /** The position at [share] (0 to 1) of the window's width. */
    fun positionAt(share: Double): Duration = start + Duration.ofMillis((span.toMillis() * share).toLong())

    /** Where [position] lies in the window, as a share of its width; outside 0 to 1 when not shown. */
    fun shareOf(position: Duration): Double = (position - start).toMillis().toDouble() / span.toMillis().coerceAtLeast(1)

    /** The window [factor] times as long (below 1 zooms in), keeping the position at [anchor] (a share of the width) in place. */
    fun zoom(factor: Double, anchor: Double): TimelineWindow {
        val pinned = positionAt(anchor)
        val span = (span.toMillis() * factor).toLong()
        return clamped(pinned.toMillis() - (span * anchor).toLong(), span)
    }

    /** The window moved later by [by] (earlier when negative). */
    fun pan(by: Duration): TimelineWindow = clamped((start + by).toMillis(), span.toMillis())

    /** A window of the same span centred on [position]. */
    fun centredOn(position: Duration): TimelineWindow = clamped(position.toMillis() - span.toMillis() / 2, span.toMillis())

    /** A window from [EVENT_MARGIN] before [from] to [EVENT_MARGIN] after [until], to show an event in its surroundings. */
    fun showing(from: Duration, until: Duration): TimelineWindow =
        clamped((from - EVENT_MARGIN).toMillis(), (until - from + EVENT_MARGIN.multipliedBy(2)).toMillis())

    /**
     * The window auto zoom shows for an event from [from] to [until]: centred on it and
     * [FRAMING_FACTOR] times as long, but no shorter than [FRAMING_MIN] and no longer than
     * [FRAMING_MAX], so a short event keeps its surroundings and a long one its detail.
     */
    fun framing(from: Duration, until: Duration): TimelineWindow {
        val span = (until - from).multipliedBy(FRAMING_FACTOR).coerceIn(FRAMING_MIN, FRAMING_MAX).toMillis()
        val middle = (from.toMillis() + until.toMillis()) / 2
        return clamped(middle - span / 2, span)
    }

    /**
     * This window while the playhead is at [position]; once the playhead has left it, a window of
     * the same span that shows it near its left edge, so it moves on with the playback.
     */
    fun following(position: Duration): TimelineWindow =
        if (position in start..end) this else clamped(position.toMillis() - span.toMillis() / 10, span.toMillis())

    private fun clamped(startMillis: Long, spanMillis: Long): TimelineWindow {
        val totalMillis = total.toMillis()
        val span = spanMillis.coerceIn(minOf(MIN_SPAN.toMillis(), totalMillis), totalMillis)
        val start = startMillis.coerceIn(0, totalMillis - span)
        return TimelineWindow(Duration.ofMillis(start), Duration.ofMillis(start + span), total)
    }

    companion object {
        /** The shortest stretch the timeline zooms in to. */
        val MIN_SPAN: Duration = Duration.ofSeconds(20)

        /** How much the timeline shows before and after an event it is asked to show. */
        val EVENT_MARGIN: Duration = Duration.ofSeconds(30)

        /** How many times an event's length auto zoom shows, within [FRAMING_MIN] and [FRAMING_MAX]. */
        const val FRAMING_FACTOR = 4L
        val FRAMING_MIN: Duration = Duration.ofSeconds(30)
        val FRAMING_MAX: Duration = Duration.ofMinutes(2)

        /** The window that shows the whole sleep. */
        fun whole(total: Duration) = TimelineWindow(Duration.ZERO, total, total)
    }
}
