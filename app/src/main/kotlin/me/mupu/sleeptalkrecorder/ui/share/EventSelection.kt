package me.mupu.sleeptalkrecorder.ui.share

import me.mupu.sleeptalkrecorder.data.SleepEvent
import java.time.Duration

/**
 * The events selected together, by id, which is unique across all sleeps: on the sleep screen or
 * among the library's tagged events. Selection mode lasts while any of them is still listed; ids of
 * events that are no longer listed select nothing.
 */
data class EventSelection(val ids: Set<Long> = emptySet()) {

    /** [event] added to the selection, or taken out of it if it was selected. */
    fun toggle(event: SleepEvent): EventSelection = EventSelection(if (event.id in ids) ids - event.id else ids + event.id)

    /** The selected ones of [events], in their order. */
    fun of(events: List<SleepEvent>): List<SleepEvent> = events.filter { it.id in ids }
}

/** How long [events] last together. */
fun totalLength(events: List<SleepEvent>): Duration = events.fold(Duration.ZERO) { sum, event -> sum + (event.end - event.start) }
