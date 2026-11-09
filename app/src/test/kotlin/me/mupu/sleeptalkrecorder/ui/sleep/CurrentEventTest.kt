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

    private fun event(id: Long, startMillis: Long, endMillis: Long) = SleepEvent(id, Duration.ofMillis(startMillis), Duration.ofMillis(endMillis), "event", null, KindColor.Theme(ThemeRole.PRIMARY))
    private fun stretch(startMillis: Long, endMillis: Long) = Stretch(Duration.ofMillis(startMillis), Duration.ofMillis(endMillis))

    private val events = listOf(event(1, 10_000, 12_000), event(2, 13_500, 14_000), event(3, 30_000, 31_000))

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
}
