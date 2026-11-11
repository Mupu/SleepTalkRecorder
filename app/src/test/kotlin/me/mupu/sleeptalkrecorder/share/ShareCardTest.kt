package me.mupu.sleeptalkrecorder.share

import me.mupu.sleeptalkrecorder.share.CardShape.LANDSCAPE
import me.mupu.sleeptalkrecorder.share.CardShape.SQUARE
import me.mupu.sleeptalkrecorder.share.CardShape.WIDE
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareCardTest {

    @Test
    fun `the cards take the shapes of a messenger's album of that many videos`() {
        assertEquals(listOf(WIDE), cardShapes(1))
        assertEquals(listOf(LANDSCAPE, LANDSCAPE), cardShapes(2))
        assertEquals(listOf(LANDSCAPE, SQUARE, SQUARE), cardShapes(3))
        assertEquals(List(5) { SQUARE }, cardShapes(5))
    }
}
