package me.mupu.sleeptalkrecorder.ui.tags

import me.mupu.sleeptalkrecorder.data.EventKindEntity
import me.mupu.sleeptalkrecorder.data.EventTag
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.TagEntity

/**
 * Which events a list shows, chosen as tags that combine with OR. With no tag chosen it shows
 * every event that is not hidden; with tags chosen, the events that have one of them. A hidden
 * event shows only while [EventTag.System.Hidden] is chosen, so hidden events stay out of every
 * other choice and can be gone through by ear and unhidden.
 */
data class TagFilter(val chosen: Set<EventTag> = emptySet()) {

    /** Whether the list shows [event] under this filter. */
    fun shows(event: SleepEvent): Boolean = when {
        event.hidden -> EventTag.System.Hidden in chosen
        chosen.isEmpty() -> true
        else -> event.tags.any { it in chosen }
    }

    /** This filter with [tag] chosen, or no longer chosen if it was. */
    fun toggle(tag: EventTag): TagFilter = TagFilter(if (tag in chosen) chosen - tag else chosen + tag)

    /** This filter without the own tag [tagId], as after it was deleted. */
    fun without(tagId: Long): TagFilter = TagFilter(chosen - EventTag.Stored(tagId))
}

/** The system tags a filter offers: one per event kind of [kinds], then murmur, named and hidden. */
fun systemTags(kinds: List<EventKindEntity>): List<EventTag.System> =
    kinds.map { EventTag.System.Kind(it.id) } + listOf(EventTag.System.Murmur, EventTag.System.Named, EventTag.System.Hidden)

/** Every tag a filter offers in the order it lists them: the user's [tags], then the [systemTags]. */
fun filterTags(tags: List<TagEntity>, kinds: List<EventKindEntity>): List<EventTag> =
    tags.map { EventTag.Stored(it.id) } + systemTags(kinds)
