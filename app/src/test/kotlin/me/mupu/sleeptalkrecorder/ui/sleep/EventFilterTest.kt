package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.data.EventKinds
import me.mupu.sleeptalkrecorder.data.SleepEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class EventFilterTest {

    private fun event(id: Long, kind: String, hidden: Boolean = false) =
        SleepEvent(id, id * 10_000, id * 10_000 + 1_000, kind, null, "primary", hidden = hidden)

    private val events = listOf(
        event(1, EventKinds.EVENT),
        event(2, EventKinds.SPEECH),
        event(3, EventKinds.EVENT, hidden = true),
        event(4, EventKinds.SPEECH, hidden = true),
        event(5, EventKinds.SPEECH),
    )

    private fun listed(filter: EventFilter) = events.filter(filter::shows).map { it.id }

    @Test
    fun `all lists every event that is not hidden`() {
        assertEquals(listOf(1L, 2L, 5L), listed(EventFilter.All))
    }

    @Test
    fun `a kind lists only its events that are not hidden`() {
        assertEquals(listOf(2L, 5L), listed(EventFilter.Kind(EventKinds.SPEECH)))
    }

    @Test
    fun `hidden lists only the hidden events, of every kind`() {
        assertEquals(listOf(3L, 4L), listed(EventFilter.Hidden))
    }
}
