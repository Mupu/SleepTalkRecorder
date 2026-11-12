package me.mupu.sleeptalkrecorder.ui.tags

import me.mupu.sleeptalkrecorder.data.SleepEvent

/** Whether a tag is on all of the events of the tag window, on none, or on only some of them. */
enum class TagState { ON, OFF, MIXED }

/**
 * Tags given to all events of the tag window ([added]) and taken from all of them ([removed]):
 * what OK applies, and the last changes the window remembers and opens with. It is stored as
 * text such as `+1,+4,-7`.
 */
data class TagChanges(val added: Set<Long> = emptySet(), val removed: Set<Long> = emptySet()) {

    /** These changes without the tag [tagId], as after it was deleted. */
    fun without(tagId: Long): TagChanges = TagChanges(added - tagId, removed - tagId)

    /** These changes only of the tags [existing], as before they are applied. */
    fun keeping(existing: Set<Long>): TagChanges = TagChanges(added intersect existing, removed intersect existing)

    /** The changes as stored text; see [parse]. */
    fun format(): String = (added.map { "+$it" } + removed.map { "-$it" }).joinToString(",")

    companion object {
        /** The changes [text] holds, without parts that cannot be read; none for null. */
        fun parse(text: String?): TagChanges {
            val parts = text?.split(',')?.map(String::trim).orEmpty()
            fun ids(sign: Char) = parts.filter { it.startsWith(sign) }.mapNotNull { it.drop(1).toLongOrNull() }.toSet()
            return TagChanges(ids('+'), ids('-'))
        }
    }
}

/**
 * The tag window's choice for its events: for every assignable tag the state it has on the events
 * ([actual]), the state the window opened with ([opened]), and the state the user chose so far
 * ([chosen]). The window opens with the last changes applied to the tags that all events have or
 * none has; a tag only some of them have always opens as a dash, whatever the last changes say.
 *
 * Tapping an empty tag gives it to all events, tapping a ticked one takes it from all; a dash goes
 * to ticked on the first tap and never comes back, since it only means "untouched, on some".
 * Untouched tags keep their state.
 */
data class TagChoices(
    val actual: Map<Long, TagState>,
    val opened: Map<Long, TagState>,
    val chosen: Map<Long, TagState>,
    /** The changes the window was opened with, which it keeps remembering while the user leaves them. */
    private val last: TagChanges,
) {

    /** The state the window shows for the tag [tagId]. */
    fun stateOf(tagId: Long): TagState = chosen[tagId] ?: TagState.OFF

    /** The choice after a tap on the tag [tagId]. */
    fun tap(tagId: Long): TagChoices {
        val next = when (stateOf(tagId)) {
            TagState.MIXED, TagState.OFF -> TagState.ON
            TagState.ON -> TagState.OFF
        }
        return copy(chosen = chosen + (tagId to next))
    }

    /** The choice with the tag [tagId], which was just made and so is on none of the events, given to all. */
    fun withNewTag(tagId: Long): TagChoices = copy(
        actual = actual + (tagId to TagState.OFF),
        opened = opened + (tagId to TagState.OFF),
        chosen = chosen + (tagId to TagState.ON),
    )

    /** The choice without the tag [tagId], as after it was deleted while the window is open. */
    fun without(tagId: Long): TagChoices = TagChoices(actual - tagId, opened - tagId, chosen - tagId, last.without(tagId))

    /** What OK applies: the tags chosen for all or for none that are not so already. */
    val changes: TagChanges
        get() = TagChanges(
            added = chosen.filter { (id, state) -> state == TagState.ON && actual[id] != TagState.ON }.keys,
            removed = chosen.filter { (id, state) -> state == TagState.OFF && actual[id] != TagState.OFF }.keys,
        )

    /**
     * What the window remembers after OK: the [changes], and the last changes the user left
     * untouched, also where the events had that state already or where they were not applied
     * because only some of the events had the tag, so the next event opens with them again.
     */
    val remembered: TagChanges
        get() {
            val kept = chosen.mapNotNull { (id, state) ->
                val untouched = state == opened[id]
                when {
                    state == TagState.MIXED -> if (untouched) lastStateOf(id)?.let { id to it } else null
                    state != actual[id] -> id to state
                    untouched -> lastStateOf(id)?.let { id to state }
                    else -> null
                }
            }.toMap()
            return TagChanges(
                added = kept.filterValues { it == TagState.ON }.keys,
                removed = kept.filterValues { it == TagState.OFF }.keys,
            )
        }

    /** The state the last changes gave the tag [tagId]; null if they left it alone. */
    private fun lastStateOf(tagId: Long): TagState? = when (tagId) {
        in last.added -> TagState.ON
        in last.removed -> TagState.OFF
        else -> null
    }

    companion object {
        /**
         * The choice for [events] over the tags [tagIds], opened with the [last] changes applied
         * to the tags all of them have or none has; the last changes of tags that no longer
         * exist are dropped.
         */
        fun of(tagIds: List<Long>, events: List<SleepEvent>, last: TagChanges): TagChoices {
            val actual = tagIds.associateWith { id ->
                val count = events.count { id in it.tagIds }
                when (count) {
                    0 -> TagState.OFF
                    events.size -> TagState.ON
                    else -> TagState.MIXED
                }
            }
            val opened = actual.mapValues { (id, state) ->
                when {
                    state == TagState.MIXED -> state
                    id in last.added -> TagState.ON
                    id in last.removed -> TagState.OFF
                    else -> state
                }
            }
            val existing = tagIds.toSet()
            return TagChoices(actual, opened, opened, TagChanges(last.added intersect existing, last.removed intersect existing))
        }
    }
}
