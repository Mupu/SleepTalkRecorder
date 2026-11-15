package me.mupu.sleeptalkrecorder.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackBoostsTest {

    @Test
    fun `the sleep and single events keep their own boost`() {
        val boosts = PlaybackBoosts().next(PlaybackKind.EVENT).next(PlaybackKind.EVENT)

        assertEquals(4, boosts.of(PlaybackKind.EVENT))
        assertEquals(1, boosts.of(PlaybackKind.SLEEP))
    }

    @Test
    fun `after the strongest boost it is off again`() {
        val strongest = PlaybackBoosts(sleep = 8)

        assertEquals(1, strongest.next(PlaybackKind.SLEEP).of(PlaybackKind.SLEEP))
    }
}
