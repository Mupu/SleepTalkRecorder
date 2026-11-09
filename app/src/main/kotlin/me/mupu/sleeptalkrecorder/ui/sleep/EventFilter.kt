package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.data.SleepEvent

/**
 * Which events the sleep screen lists, and so which cut playback plays. The choices exclude each
 * other: hidden events are left out of every choice but [Hidden], which lists only them, so they
 * can be gone through by ear and unhidden.
 */
sealed interface EventFilter {

    /** Whether the list shows [event] under this filter. */
    fun shows(event: SleepEvent): Boolean

    /** Every event that is not hidden. */
    data object All : EventFilter {
        override fun shows(event: SleepEvent) = !event.hidden
    }

    /** The events of the kind [kindId], such as speech, that are not hidden. */
    data class Kind(val kindId: String) : EventFilter {
        override fun shows(event: SleepEvent) = !event.hidden && event.kindId == kindId
    }

    /** Only the hidden events, of every kind. */
    data object Hidden : EventFilter {
        override fun shows(event: SleepEvent) = event.hidden
    }
}
