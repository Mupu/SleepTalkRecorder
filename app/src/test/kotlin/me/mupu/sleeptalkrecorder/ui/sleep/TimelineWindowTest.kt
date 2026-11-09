package me.mupu.sleeptalkrecorder.ui.sleep

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineWindowTest {

    private val hour = Duration.ofHours(1)
    private fun minutes(n: Long) = Duration.ofMinutes(n)

    @Test
    fun `zooming in keeps the moment under the fingers in place`() {
        val zoomed = TimelineWindow.whole(hour).zoom(factor = 0.5, anchor = 0.25)

        assertEquals(TimelineWindow(Duration.ofSeconds(450), Duration.ofSeconds(2_250), hour), zoomed)
    }

    @Test
    fun `the window never leaves the sleep nor grows beyond it`() {
        val window = TimelineWindow(minutes(50), minutes(60), hour)

        assertEquals(TimelineWindow(minutes(50), minutes(60), hour), window.pan(minutes(30)))
        assertEquals(TimelineWindow.whole(hour), window.zoom(factor = 20.0, anchor = 0.5))
    }

    @Test
    fun `zooming in stops at a span of 20 seconds`() {
        val window = TimelineWindow.whole(hour).zoom(factor = 0.0001, anchor = 0.0)

        assertEquals(Duration.ofSeconds(20), window.span)
    }

    @Test
    fun `showing an event frames it with room around it`() {
        val window = TimelineWindow.whole(hour).showing(minutes(30), minutes(30).plusSeconds(10))

        assertEquals(TimelineWindow(Duration.ofSeconds(1_770), Duration.ofSeconds(1_840), hour), window)
    }

    @Test
    fun `a share of the width maps to a position in the window`() {
        val window = TimelineWindow(minutes(10), minutes(20), hour)

        assertEquals(minutes(15), window.positionAt(0.5))
        assertEquals(0.25, window.shareOf(Duration.ofSeconds(750)), 1e-9)
    }
}
