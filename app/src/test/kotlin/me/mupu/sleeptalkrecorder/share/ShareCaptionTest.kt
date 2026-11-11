package me.mupu.sleeptalkrecorder.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareCaptionTest {

    private val named = EventLabels("First words", "Speech", "8 Nov, 22:55:41", "“Test 1, 2, 3.”", "Night of 8 to 9 November", "2.4 s")
    private val plain = EventLabels(null, "Event", "8 Nov, 23:10:05", null, "Night of 8 to 9 November", "0.8 s")

    @Test
    fun `a line names the chosen items the event has, in a fixed order`() {
        assertEquals("First words · 8 Nov, 22:55:41 · “Test 1, 2, 3.”", caption(listOf(named), CaptionItem.DEFAULT))
        assertEquals("8 Nov, 23:10:05", caption(listOf(plain), CaptionItem.DEFAULT))
        assertEquals("Night of 8 to 9 November · 2.4 s · “Test 1, 2, 3.”", caption(listOf(named), setOf(CaptionItem.TRANSCRIPT, CaptionItem.LENGTH, CaptionItem.SLEEP)))
    }

    @Test
    fun `several events take a numbered line each, and a caption that names nothing is none`() {
        assertEquals("1. First words · 8 Nov, 22:55:41\n2. 8 Nov, 23:10:05", caption(listOf(named, plain), setOf(CaptionItem.NAME, CaptionItem.TIME)))
        assertEquals("2. First words", caption(listOf(plain, named), setOf(CaptionItem.NAME)))
        assertNull(caption(listOf(named), emptySet()))
    }
}
