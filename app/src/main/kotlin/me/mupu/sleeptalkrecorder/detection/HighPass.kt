package me.mupu.sleeptalkrecorder.detection

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A 4th-order Butterworth high-pass at [cutoffHz], as two second-order sections, that filters a
 * stream of samples piece by piece and keeps its state between pieces.
 *
 * It removes rumble below the cutoff (traffic, a fan's hum), so a sound tagger hears the voice and
 * breath above it. The sections are the bilinear transform with the cutoff prewarped, the same
 * filter as SciPy's `butter(4, cutoff, "high", fs=rate, output="sos")` run with `sosfilt`, with
 * which the detection pipeline was measured; the arithmetic runs in doubles like SciPy's.
 */
class HighPass(cutoffHz: Double, sampleRate: Int) {

    /** The coefficients of each section: b0, b1, b2, a1, a2 (a0 is 1). */
    internal val sections: List<DoubleArray> = SECTION_Q.map { q ->
        val w0 = 2 * PI * cutoffHz / sampleRate
        val alpha = sin(w0) / (2 * q)
        val a0 = 1 + alpha
        val b = (1 + cos(w0)) / 2 / a0
        doubleArrayOf(b, -2 * b, b, -2 * cos(w0) / a0, (1 - alpha) / a0)
    }

    // Transposed direct form II, two state values per section.
    private val state = Array(sections.size) { DoubleArray(2) }

    /** [samples] filtered, continuing from the end of the previous piece. */
    fun filter(samples: FloatArray): FloatArray {
        val out = FloatArray(samples.size)
        for (i in samples.indices) {
            var x = samples[i].toDouble()
            for (s in sections.indices) {
                val (b0, b1, b2, a1, a2) = sections[s]
                val z = state[s]
                val y = b0 * x + z[0]
                z[0] = b1 * x - a1 * y + z[1]
                z[1] = b2 * x - a2 * y
                x = y
            }
            out[i] = x.toFloat()
        }
        return out
    }

    private companion object {
        /** The quality factors of a 4th-order Butterworth filter's two pole pairs. */
        val SECTION_Q = doubleArrayOf(1 / (2 * sin(3 * PI / 8)), 1 / (2 * sin(PI / 8)))
    }
}
