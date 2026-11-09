package me.mupu.sleeptalkrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class KindColorTest {

    @Test
    fun `a stored colour reads as a theme role or a fixed colour`() {
        assertEquals(KindColor.Theme(ThemeRole.TERTIARY), KindColor.parse("tertiary"))
        assertEquals(KindColor.Fixed(0x3366CC), KindColor.parse("#3366cc"))
    }

    @Test
    fun `a colour the app cannot read is the primary colour`() {
        assertEquals(KindColor.Theme(ThemeRole.PRIMARY), KindColor.parse("#12"))
        assertEquals(KindColor.Theme(ThemeRole.PRIMARY), KindColor.parse("violet"))
    }

    @Test
    fun `a colour is stored as it is read`() {
        for (color in listOf(KindColor.Theme(ThemeRole.SECONDARY), KindColor.Fixed(0x0A0B0C))) {
            assertEquals(color, KindColor.parse(color.spec))
        }
    }
}
