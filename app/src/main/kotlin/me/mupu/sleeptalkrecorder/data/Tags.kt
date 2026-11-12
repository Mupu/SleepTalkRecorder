package me.mupu.sleeptalkrecorder.data

import me.mupu.sleeptalkrecorder.detection.FoundBy

/** The tag the app provides; the database adds it each time it opens if it is missing. */
object Tags {

    /** The id of Favourite, the tag the app provides; own tags get the ids after it. */
    const val FAVOURITE: Long = 1

    /** Favourite as it is stored. */
    val favourite = TagEntity(id = FAVOURITE, type = TagType.FAVOURITE, name = null)
}

/**
 * A tag an event can have, as a filter chooses it: one of the user's ([Stored]) or a system tag
 * the app sets on its own ([System]), which can be filtered by but neither given nor taken away.
 */
sealed interface EventTag {

    /** Favourite or an own tag of the user, by its [TagEntity.id]. */
    data class Stored(val id: Long) : EventTag

    /** A tag that follows from what the app knows about an event; see [SleepEvent.tags]. */
    sealed interface System : EventTag {

        /** The user gave the event a name. */
        data object Named : System

        /** The user hid the event. */
        data object Hidden : System

        /** The event is of the kind [kindId], such as speech, murmur or noise. */
        data class Kind(val kindId: String) : System

        /**
         * The second way of finding events sorted the event as background. Such an event is left
         * out of the list unless this tag is chosen ([SleepEvent.background]).
         */
        data object Background : System

        /** The second way of finding events could not tell whether the event is human or background. */
        data object Unsure : System

        /** What in the second way of finding events found the event: loudness, listening or both. */
        data class Found(val by: FoundBy) : System

        /** In a comparison of both ways of finding events, which of them found the event. */
        data class Ways(val found: WaysFound) : System
    }
}
