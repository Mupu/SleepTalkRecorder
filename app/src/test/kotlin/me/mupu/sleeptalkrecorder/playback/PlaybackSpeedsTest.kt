package me.mupu.sleeptalkrecorder.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSpeedsTest {

    @Test
    fun `the sleep and single events keep their own speed`() {
        val speeds = PlaybackSpeeds().next(PlaybackKind.EVENT).next(PlaybackKind.EVENT)

        assertEquals(1.5f, speeds.of(PlaybackKind.EVENT))
        assertEquals(1f, speeds.of(PlaybackKind.SLEEP))
    }

    @Test
    fun `after the fastest speed comes the slowest`() {
        val fastest = PlaybackSpeeds(sleep = 3f)

        assertEquals(0.75f, fastest.next(PlaybackKind.SLEEP).of(PlaybackKind.SLEEP))
    }
}
