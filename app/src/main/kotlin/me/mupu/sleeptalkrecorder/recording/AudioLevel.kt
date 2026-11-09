package me.mupu.sleeptalkrecorder.recording

import kotlin.math.log10
import kotlin.math.sqrt

/** Loudness of 16-bit PCM in dBFS (decibels relative to full scale). */
internal object AudioLevel {

    /** The quietest level reported; digital silence would otherwise be minus infinity. */
    const val FLOOR_DBFS = -96.0

    private const val FULL_SCALE = 32_768.0

    /** The RMS level of the first [count] samples of [samples], in dBFS. */
    fun rmsDbfs(samples: ShortArray, count: Int): Double {
        if (count <= 0) return FLOOR_DBFS

        var sumOfSquares = 0.0
        for (i in 0 until count) {
            val sample = samples[i].toDouble()
            sumOfSquares += sample * sample
        }
        val rms = sqrt(sumOfSquares / count)
        if (rms == 0.0) return FLOOR_DBFS

        return maxOf(FLOOR_DBFS, 20 * log10(rms / FULL_SCALE))
    }
}
