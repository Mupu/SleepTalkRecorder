package me.mupu.sleeptalkrecorder.ui.format

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class PositionFormatTest {

    @Test
    fun `a position is shown as hours, minutes and whole seconds`() {
        assertEquals("6:10:00", formatPosition(Duration.ofHours(6).plusMinutes(10)))
        assertEquals("0:00:59", formatPosition(Duration.ofMillis(59_999)))
        assertEquals("12:03:07", formatPosition(Duration.ofSeconds(12 * 3600 + 3 * 60 + 7)))
    }
}
