package me.mupu.sleeptalkrecorder.ui.settings

import me.mupu.sleeptalkrecorder.time.SleepWindows
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepWindowsDragTest {

    private val defaults = SleepWindows.DEFAULT

    private fun minute(hour: Int, minute: Int = 0) = (hour * 60 + minute).toFloat()

    private fun windows(naps: String, nights: String) = SleepWindows(LocalTime.parse(naps), LocalTime.parse(nights))

    @Test
    fun `a dragged thumb snaps to the nearest quarter hour`() {
        assertEquals(windows("13:00", "18:00"), defaults.dragged(WindowThumb.NAPS, minute(13, 7)))
        assertEquals(windows("12:00", "21:15"), defaults.dragged(WindowThumb.NIGHTS, minute(21, 8)))
    }

    @Test
    fun `a thumb stops at the other one, which puts both starts together and leaves no naps`() {
        assertEquals(windows("18:00", "18:00"), defaults.dragged(WindowThumb.NAPS, minute(20)))
        assertEquals(windows("12:00", "12:00"), defaults.dragged(WindowThumb.NIGHTS, minute(9)))
    }

    @Test
    fun `a thumb stays within the day, the moon at a quarter to midnight at the latest`() {
        assertEquals(windows("00:00", "18:00"), defaults.dragged(WindowThumb.NAPS, -40f))
        assertEquals(windows("12:00", "23:45"), defaults.dragged(WindowThumb.NIGHTS, minute(25)))
    }

    @Test
    fun `a thumb stops exactly at another that was set off the quarter hours`() {
        assertEquals(windows("18:07", "18:07"), windows("12:00", "18:07").dragged(WindowThumb.NAPS, minute(18, 10)))
    }

    @Test
    fun `a drag takes the nearer thumb, and of two at the same time the one on the side it moves to`() {
        assertEquals(WindowThumb.NAPS, defaults.thumbFor(minute(14), delta = 5f))
        assertEquals(WindowThumb.NIGHTS, defaults.thumbFor(minute(16), delta = -5f))

        val together = windows("18:00", "18:00")
        assertEquals(WindowThumb.NAPS, together.thumbFor(minute(18), delta = -1f))
        assertEquals(WindowThumb.NIGHTS, together.thumbFor(minute(18), delta = 1f))
    }
}
