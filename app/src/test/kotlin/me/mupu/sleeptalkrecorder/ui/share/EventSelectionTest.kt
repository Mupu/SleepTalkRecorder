package me.mupu.sleeptalkrecorder.ui.share

import me.mupu.sleeptalkrecorder.data.KindColor
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.ThemeRole
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class EventSelectionTest {

    private fun event(id: Long, from: Long, until: Long) =
        SleepEvent(id, Duration.ofMillis(from), Duration.ofMillis(until), "event", null, KindColor.Theme(ThemeRole.PRIMARY))

    private val events = listOf(event(1, 0, 1_500), event(2, 5_000, 7_000), event(3, 9_000, 9_400))

    @Test
    fun `a tap adds an event or takes it out again, and the selection keeps the list's order`() {
        val selection = EventSelection().toggle(events[2]).toggle(events[0]).toggle(events[1]).toggle(events[2])

        assertEquals(listOf(events[0], events[1]), selection.of(events))
        assertEquals(Duration.ofMillis(3_500), totalLength(selection.of(events)))
    }

    @Test
    fun `an event that is no longer found leaves the selection`() {
        val selection = EventSelection().toggle(events[0]).toggle(events[1])

        assertEquals(listOf(events[1]), selection.of(events.drop(1)))
        assertEquals(emptyList<SleepEvent>(), selection.of(events.drop(2)))
    }
}
