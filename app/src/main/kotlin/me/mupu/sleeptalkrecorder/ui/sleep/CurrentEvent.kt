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
