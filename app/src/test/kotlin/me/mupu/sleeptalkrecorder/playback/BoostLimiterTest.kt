package me.mupu.sleeptalkrecorder.playback

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BoostLimiterTest {

    private val limiter = BoostLimiter(SAMPLE_RATE)

    @Test
    fun `quiet samples get the full boost`() {
        assertEquals(800, process(100, gain = 8f, boost = 8f))
        assertEquals(-800, process(-100, gain = 8f, boost = 8f))
    }

    @Test
    fun `without a boost samples are only multiplied`() {
        assertEquals(32_767, process(32_767, gain = 1f, boost = 1f))
        assertEquals(-32_768, process(-32_768, gain = 1f, boost = 1f))
        assertEquals(16_000, process(32_000, gain = 0.5f, boost = 1f))
    }

    @Test
    fun `a sudden loud sample is held at full scale`() {
        assertEquals(32_767, process(20_000, gain = 8f, boost = 8f))
        assertEquals(-32_768, process(-20_000, gain = 8f, boost = 8f))
    }

    @Test
    fun `a loud steady tone comes out near the ceiling as the same sine`() {
        val input = IntArray(SAMPLE_RATE / 2) { (16_000 * sin(2 * PI * 200 * it / SAMPLE_RATE)).toInt() }
        val output = input.map { process(it, gain = 8f, boost = 8f) }

        // After the attack: the scale that fits best, and how far the output strays from it.
        val tail = input.indices.drop(input.size - SAMPLE_RATE / 10)
        val scale = tail.sumOf { output[it].toDouble() * input[it] } / tail.sumOf { input[it].toDouble() * input[it] }
        val peak = tail.maxOf { abs(output[it]) }
        val stray = tail.maxOf { abs(output[it] - scale * input[it]) }

        assertTrue("peak $peak", peak in 28_000..30_500)
        assertTrue("stray $stray", stray < 0.02 * peak)
    }

    private fun process(sample: Int, gain: Float, boost: Float): Int {
        val frame = shortArrayOf(sample.toShort())
        limiter.process(frame, gain, boost)
        return frame[0].toInt()
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
    }
}
