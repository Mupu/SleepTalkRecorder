package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration

/**
 * The event of [events] (sorted by start) at the playhead's [position]: the one that lies under it,
 * or whose lead-in or tail does. [played] holds each event's stretch with lead-in and tail as cut
 * playback plays it, in the same order. Where the tail of one event meets the lead-in of the next,
 * the next one is current, since that is where playback is heading. Null between events.
 */
fun currentEvent(events: List<SleepEvent>, played: List<Stretch>, position: Duration): SleepEvent? =
    events.firstOrNull { position >= it.start && position < it.end }
        ?: events.indices.lastOrNull { position >= played[it].start && position < played[it].end }?.let(events::get)

/** The events [neighbourEvents] steps to from a position; null where there is none in that direction. */
data class NeighbourEvents(val previous: SleepEvent?, val next: SleepEvent?)

/**
 * The events of [events] (sorted by start) before and after the playhead's [position], to step
 * between them. [played] holds each event's stretch with lead-in and tail as cut playback plays
 * it, in the same order; a step goes to the start of such a stretch. The playhead is at the last
 * event whose stretch has begun: "next" is the one after it, "previous" the one before it while
 * its stretch still plays, and the event itself once the stretch is over. Only the stretch starts
 * count, unlike for [currentEvent], so a step lands where the following step continues from, even
 * where a long lead-in begins inside the event before. The player seeks and reports in whole
 * milliseconds, so a stretch that begins less than one after [position] counts as begun; else a
 * step to a start cut back to a recording's start, which need not be a whole millisecond, would
 * land just before it and the next step would go there again.
 */
fun neighbourEvents(events: List<SleepEvent>, played: List<Stretch>, position: Duration): NeighbourEvents {
    val begun = played.indexOfLast { it.start < position + ONE_MILLISECOND }
    val previous = when {
        begun < 0 -> null
        position < played[begun].end -> events.getOrNull(begun - 1)
        else -> events[begun]
    }
    return NeighbourEvents(previous, events.getOrNull(begun + 1))
}

private val ONE_MILLISECOND: Duration = Duration.ofMillis(1)
