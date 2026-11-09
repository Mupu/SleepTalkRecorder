package me.mupu.sleeptalkrecorder.detection

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * An in-place radix-2 FFT of a fixed power-of-two [size]. Twiddle factors and the bit-reversal
 * order are computed once, so a transform allocates nothing; the feature extractor runs 50 of them
 * per second of audio.
 */
internal class Fft(private val size: Int) {

    init {
        require(size >= 2 && size and (size - 1) == 0) { "size must be a power of two, was $size" }
    }

    private val cosines = DoubleArray(size / 2) { cos(-2 * PI * it / size) }
    private val sines = DoubleArray(size / 2) { sin(-2 * PI * it / size) }
    private val reversed = IntArray(size).also { order ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) order[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** Replaces [re] and [im] (each of [size] values) with their discrete Fourier transform. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        var half = 1
        while (half < size) {
            val step = size / (half * 2)
            for (start in 0 until size step half * 2) {
                for (k in 0 until half) {
                    val wr = cosines[k * step]
                    val wi = sines[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
            }
            half *= 2
        }
    }
}
