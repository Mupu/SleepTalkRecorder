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

        assertEquals(TimelineWindow(minutes(50), minutes(60), hour), window.moved(from = 0.0, to = -3.0))
        assertEquals(TimelineWindow.whole(hour), window.zoom(factor = 20.0, anchor = 0.5))
    }

    @Test
    fun `dragging carries the moment under the finger along`() {
        val moved = TimelineWindow(minutes(10), minutes(20), hour).moved(from = 0.5, to = 0.25)

        assertEquals(TimelineWindow(Duration.ofSeconds(750), Duration.ofSeconds(1_350), hour), moved)
    }

    @Test
    fun `pinching zooms around the fingers and pans with them at once`() {
        val pinched = TimelineWindow.whole(hour).moved(from = 0.5, to = 0.25, zoom = 2.0)

        assertEquals(TimelineWindow(Duration.ofSeconds(1_350), Duration.ofSeconds(3_150), hour), pinched)
    }

    @Test
    fun `zooming in past the limit keeps the moment under the fingers in place`() {
        val zoomed = TimelineWindow(minutes(10), minutes(11), hour).zoom(factor = 0.0001, anchor = 0.5)

        assertEquals(TimelineWindow(Duration.ofSeconds(620), Duration.ofSeconds(640), hour), zoomed)
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

    @Test
    fun `auto zoom frames an event with four times its length, from 30 seconds to 2 minutes`() {
        val whole = TimelineWindow.whole(hour)
        val framed = whole.framing(Duration.ofSeconds(1_780), Duration.ofSeconds(1_790))

        assertEquals(TimelineWindow(Duration.ofSeconds(1_765), Duration.ofSeconds(1_805), hour), framed)
        assertEquals(Duration.ofSeconds(30), whole.framing(minutes(30), minutes(30).plusSeconds(1)).span)
        assertEquals(minutes(2), whole.framing(minutes(30), minutes(31)).span)
    }

    @Test
    fun `a playhead that leaves the window takes the window along`() {
        val window = TimelineWindow(minutes(10), minutes(20), hour)

        assertEquals(window, window.following(minutes(15)))
        assertEquals(TimelineWindow(minutes(20), minutes(30), hour), window.following(minutes(21)))
    }
}
