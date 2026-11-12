package me.mupu.sleeptalkrecorder.ui.tags

import me.mupu.sleeptalkrecorder.data.EventKindEntity
import me.mupu.sleeptalkrecorder.data.EventTag
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.TagEntity

/**
 * Which events a list shows, chosen as tags that combine with OR. With no tag chosen it shows
 * every event that is neither hidden nor background; with tags chosen, the events that have one
 * of them. A hidden event shows only while [EventTag.System.Hidden] is chosen, and a background
 * event ([SleepEvent.background]) only while [EventTag.System.Background] is, so they stay out of
 * every other choice and can be gone through by ear.
 */
data class TagFilter(val chosen: Set<EventTag> = emptySet()) {

    /** Whether the list shows [event] under this filter. */
    fun shows(event: SleepEvent): Boolean = when {
        event.hidden -> EventTag.System.Hidden in chosen
        event.background -> EventTag.System.Background in chosen
        chosen.isEmpty() -> true
        else -> event.tags.any { it in chosen }
    }

    /** This filter with [tag] chosen, or no longer chosen if it was. */
    fun toggle(tag: EventTag): TagFilter = TagFilter(if (tag in chosen) chosen - tag else chosen + tag)

    /** This filter without the own tag [tagId], as after it was deleted. */
    fun without(tagId: Long): TagFilter = TagFilter(chosen - EventTag.Stored(tagId))
}

/**
 * The system tags a filter offers: one per event kind of [kinds], then named and hidden, then the
 * [extra] ones of the events listed, such as those of the second way of finding events.
 */
fun systemTags(kinds: List<EventKindEntity>, extra: List<EventTag.System> = emptyList()): List<EventTag.System> =
    kinds.map { EventTag.System.Kind(it.id) } + listOf(EventTag.System.Named, EventTag.System.Hidden) + extra

/** Every tag a filter offers in the order it lists them: the user's [tags], then the [systemTags]. */
fun filterTags(tags: List<TagEntity>, kinds: List<EventKindEntity>, extra: List<EventTag.System> = emptyList()): List<EventTag> =
    tags.map { EventTag.Stored(it.id) } + systemTags(kinds, extra)
