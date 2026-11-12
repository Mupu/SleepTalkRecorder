package me.mupu.sleeptalkrecorder.detection

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The start threshold of the last hour on synthetic speech-band levels (50 hops per second). */
class SlidingThresholdTest {

    /** 10 quiet minutes, an hour of rain, then 20 quiet minutes again. */
    private val night = floor(minutes = 10, spread = 0.5) + floor(minutes = 60, spread = 4.0, hold = 25, seed = 2) + floor(minutes = 20, spread = 0.5, seed = 3)

    @Test
    fun `the last hour's threshold is final half a minute and a step after each moment, from the first hour on`() {
        val params = DetectionParams(thresholdWindow = SlidingThreshold.LOOK_BACK)
        val whole = EventDetector.detect(trackOf(night), params)
        val recorded = hops(80 * 60.0)
        val known = recorded - hops(40.0)
        val prefix = EventDetector.detect(trackOf(night.copyOf(recorded)), params)

        // The hour of rain raises the threshold at its end, which the prefix knows as well.
        assertTrue(whole.thresholdAt(hops(70 * 60.0)) > whole.thresholdDb + 3f)
        assertArrayEquals(whole.automatic!!.copyOfRange(0, known), prefix.automatic!!.copyOfRange(0, known), 0f)
    }

    private fun hops(seconds: Double) = (seconds * 50).toInt()

    /** A noise floor at -60 dBFS with a Gaussian spread of [spread] dB, a new value every [hold] hops. */
    private fun floor(minutes: Int, spread: Double, hold: Int = 1, seed: Int = 1): DoubleArray {
        val random = java.util.Random(seed.toLong())
        val values = DoubleArray(minutes * 60 * 50 / hold + 1) { -60 + random.nextGaussian() * spread }
        return DoubleArray(minutes * 60 * 50) { values[it / hold] }
    }

    /** A track with [speechDb] as its speech band and as its full-band level. */
    private fun trackOf(speechDb: DoubleArray): FeatureTrack {
        val codes = ByteArray(speechDb.size) { FeatureTrack.levelCode(speechDb[it]) }
        val zeros = ByteArray(speechDb.size)
        return FeatureTrack(codes, codes, zeros, codes, zeros, zeros, zeros, zeros)
    }
}
