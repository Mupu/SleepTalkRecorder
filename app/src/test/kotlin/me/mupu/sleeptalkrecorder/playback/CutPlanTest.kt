package me.mupu.sleeptalkrecorder.playback

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CutPlanTest {

    private fun s(seconds: Double) = Duration.ofMillis((seconds * 1000).toLong())
    private fun stretch(from: Double, until: Double) = Stretch(s(from), s(until))

    @Test
    fun `overlapping stretches play as one, touching ones stay apart`() {
        val plan = CutPlan.of(listOf(stretch(20.0, 22.0), stretch(8.5, 12.5), stretch(12.0, 14.5), stretch(22.0, 23.0)))

        assertEquals(listOf(stretch(8.5, 14.5), stretch(20.0, 22.0), stretch(22.0, 23.0)), plan.stretches)
    }

    @Test
    fun `playback continues inside a stretch, else at the start of the next one`() {
        val plan = CutPlan.of(listOf(stretch(8.5, 12.5), stretch(28.5, 31.5)))

        assertEquals(s(8.5), plan.continueFrom(s(0.0)))
        assertEquals(s(11.0), plan.continueFrom(s(11.0)))
        assertEquals(s(28.5), plan.continueFrom(s(12.5)))
        assertNull(plan.continueFrom(s(31.5)))
    }

    @Test
    fun `the gain fades in and out at the edges of each stretch and is silent between them`() {
        // 1 000 samples per second, so a frame is a millisecond; the fade is 15 ms.
        val envelope = CutPlan.of(listOf(stretch(8.5, 12.5))).envelope(sampleRate = 1_000)

        assertEquals(0f, envelope.gainAt(8_000), 0f)
        assertEquals(0f, envelope.gainAt(8_500), 0f)
        assertEquals(0.4f, envelope.gainAt(8_506), 1e-6f)
        assertEquals(1f, envelope.gainAt(11_000), 0f)
        assertEquals(0.2f, envelope.gainAt(12_497), 1e-6f)
        assertEquals(0f, envelope.gainAt(12_500), 0f)
    }
}
