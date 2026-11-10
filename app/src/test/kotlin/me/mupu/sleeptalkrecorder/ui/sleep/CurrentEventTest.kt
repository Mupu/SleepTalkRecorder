package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.data.KindColor
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.ThemeRole
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CurrentEventTest {

    private fun event(id: Long, startMillis: Long, endMillis: Long, favourite: Boolean = false) =
        SleepEvent(id, Duration.ofMillis(startMillis), Duration.ofMillis(endMillis), "event", null, KindColor.Theme(ThemeRole.PRIMARY), favourite = favourite)
    private fun stretch(startMillis: Long, endMillis: Long) = Stretch(Duration.ofMillis(startMillis), Duration.ofMillis(endMillis))

    private val events = listOf(event(1, 10_000, 12_000, favourite = true), event(2, 13_500, 14_000), event(3, 30_000, 31_000, favourite = true))

    // The third event's lead-in was cut back at the start of a recording at 29.5 s.
    private val played = listOf(stretch(8_500, 12_500), stretch(12_000, 14_500), stretch(29_500, 31_500))
    private fun at(millis: Long) = currentEvent(events, played, Duration.ofMillis(millis))?.id

    @Test
    fun `the event under the playhead is current, also during the lead-in and tail that are played`() {
        assertEquals(1L, at(11_000))
        assertEquals(3L, at(29_600))
        assertEquals(3L, at(31_400))
        assertNull(at(29_000))
    }

    @Test
    fun `where one event's tail meets the next one's lead-in, the next one is current`() {
        assertEquals(2L, at(12_200))
        assertEquals(1L, at(11_900))
    }

    private fun neighbours(millis: Long, filter: EventFilter = EventFilter.All): Pair<Long?, Long?> {
        val listed = events.indices.filter { filter.shows(events[it]) }
        val (previous, next) = neighbourEvents(listed.map(events::get), listed.map(played::get), Duration.ofMillis(millis))
        return previous?.id to next?.id
    }

    @Test
    fun `previous and next are the events before and after the stretch that has begun`() {
        assertEquals(null to 1L, neighbours(5_000))
        assertEquals(null to 2L, neighbours(8_500))
        assertEquals(1L to 3L, neighbours(12_200))
        assertEquals(2L to 3L, neighbours(20_000))
        assertEquals(2L to null, neighbours(30_500))
        assertEquals(3L to null, neighbours(40_000))
    }

    @Test
    fun `only the listed events count`() {
        assertEquals(1L to 3L, neighbours(13_600, EventFilter.Favourites))
        assertEquals(null to 3L, neighbours(11_000, EventFilter.Favourites))
    }

    @Test
    fun `a stretch that starts within the millisecond after the position has begun`() {
        val cutBack = listOf(Stretch(Duration.ofMillis(8_500).plusNanos(400_000), Duration.ofMillis(12_500))) + played.drop(1)
        assertEquals(2L, neighbourEvents(events, cutBack, Duration.ofMillis(8_500)).next?.id)
    }
}
