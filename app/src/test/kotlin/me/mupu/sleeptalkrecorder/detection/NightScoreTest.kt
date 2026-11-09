package me.mupu.sleeptalkrecorder.detection

import org.junit.Assert.assertEquals
import org.junit.Test

class NightScoreTest {

    private val labels = listOf(
        Label(Span(10.0, 12.0), "speech"),
        Label(Span(30.0, 31.0), "whisper"),
        Label(Span(50.0, 52.0), "snore"),
    )

    @Test
    fun `an event within a second of a label finds it and one near no label is false`() {
        val events = listOf(Span(12.8, 14.0), Span(52.5, 53.0), Span(70.0, 71.0))

        val score = NightScore.of(labels, events, length = 100.0, leadIn = 0.0, tail = 0.0)

        assertEquals(KindScore(labels = 1, found = 1), score.speechByKind["speech"])
        assertEquals(KindScore(labels = 1, found = 0), score.speechByKind["whisper"])
        assertEquals(null, score.speechByKind["snore"])
        assertEquals(KindScore(labels = 2, found = 1), score.speech)
        assertEquals(1, score.falseEvents)
    }

    @Test
    fun `cut playback plays overlapping stretches once and stays within the night`() {
        val events = listOf(Span(1.0, 2.0), Span(3.0, 4.0), Span(9.0, 9.8))

        val score = NightScore.of(labels, events, length = 10.0, leadIn = 1.5, tail = 0.5)

        // 0 to 4.5 joined, then 7.5 to the end of the night at 10.
        assertEquals(7.0, score.cutSeconds, 1e-9)
    }
}
