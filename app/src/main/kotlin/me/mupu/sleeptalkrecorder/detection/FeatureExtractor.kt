package me.mupu.sleeptalkrecorder.detection

import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.HOP_SAMPLES
import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.RECORD_BYTES
import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.levelCode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns 16 kHz mono PCM into the records of a [FeatureTrack] while it is recorded: [push] takes
 * buffers of any length and calls [onHop] with the 8-byte record of every completed hop of
 * [HOP_SAMPLES] samples, in [FeatureTrack]'s column order. The record array is reused, so
 * [onHop] must copy or write it before returning.
 *
 * RMS, peak and zero crossings are taken over the hop's own samples, so hops tile the audio
 * exactly. The band levels, flatness and voicing need more context and use the last
 * [WINDOW] samples (32 ms) up to the end of the hop; before the first 512 samples that window is
 * padded with silence.
 */
class FeatureExtractor(private val onHop: (ByteArray) -> Unit) {

    private val hop = ShortArray(HOP_SAMPLES)
    private var filled = 0

    /** The last [WINDOW] samples as fractions of full scale, oldest first. */
    private val window = DoubleArray(WINDOW)
    private val re = DoubleArray(WINDOW)
    private val im = DoubleArray(WINDOW)
    private val squares = DoubleArray(WINDOW + 1)
    private val fft = Fft(WINDOW)
    private val record = ByteArray(RECORD_BYTES)

    /** Adds the first [count] samples of [samples]. */
    fun push(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val n = min(count - offset, HOP_SAMPLES - filled)
            samples.copyInto(hop, filled, offset, offset + n)
            filled += n
            offset += n
            if (filled == HOP_SAMPLES) {
                extractHop()
                filled = 0
            }
        }
    }

    private fun extractHop() {
        var sumOfSquares = 0.0
        var peak = 0
        var crossings = 0
        var previous = hop[0].toInt()
        for (sample in hop) {
            val value = sample.toInt()
            sumOfSquares += value.toDouble() * value
            peak = max(peak, abs(value))
            if ((value xor previous) < 0) crossings++
            previous = value
        }

        window.copyInto(window, 0, HOP_SAMPLES, WINDOW)
        for (i in 0 until HOP_SAMPLES) window[WINDOW - HOP_SAMPLES + i] = hop[i] / FULL_SCALE

        record[RMS] = levelCode(powerDb(sumOfSquares / HOP_SAMPLES / (FULL_SCALE * FULL_SCALE)))
        record[PEAK] = levelCode(20 * log10(peak / FULL_SCALE + 1e-7))
        record[ZERO_CROSSINGS] = min(crossings, 255).toByte()
        spectrum()
        record[VOICING] = (voicing() * 255).roundToInt().coerceIn(0, 255).toByte()
        onHop(record)
    }

    /** Band levels and flatness from the Hann-windowed spectrum of [window]. */
    private fun spectrum() {
        for (i in 0 until WINDOW) {
            re[i] = window[i] * HANN[i]
            im[i] = 0.0
        }
        fft.transform(re, im)

        var low = 0.0
        var speech = 0.0
        var high = 0.0
        var logSum = 0.0
        for (k in 1 until WINDOW / 2) {
            val power = re[k] * re[k] + im[k] * im[k]
            when {
                k < SPEECH_FIRST_BIN -> low += power
                k <= SPEECH_LAST_BIN -> {
                    speech += power
                    logSum += ln(power + POWER_FLOOR)
                }
                else -> high += power
            }
        }

        record[LOW] = levelCode(bandDb(low))
        record[SPEECH] = levelCode(bandDb(speech))
        record[HIGH] = levelCode(bandDb(high))

        // Geometric over arithmetic mean of the band's power: 1 for white noise, near 0 for a tone.
        val bins = SPEECH_LAST_BIN - SPEECH_FIRST_BIN + 1
        val flatness = exp(logSum / bins) / (speech / bins + POWER_FLOOR)
        record[FLATNESS] = (flatness * 255).roundToInt().coerceIn(0, 255).toByte()
    }

    /**
     * The largest normalised cross-correlation of [window] with itself shifted by a pitch period
     * of 2.5 to 14 ms. Normalising by the energy of both overlapping parts keeps it independent of
     * level and window shape: 1 for a periodic sound, near 0 for noise.
     */
    private fun voicing(): Double {
        squares[0] = 0.0
        for (i in 0 until WINDOW) squares[i + 1] = squares[i] + window[i] * window[i]
        if (squares[WINDOW] < ENERGY_FLOOR) return 0.0

        var best = 0.0
        for (lag in MIN_LAG..MAX_LAG) {
            var product = 0.0
            for (i in 0 until WINDOW - lag) product += window[i] * window[i + lag]
            if (product <= 0) continue
            val energy = squares[WINDOW - lag] * (squares[WINDOW] - squares[lag])
            if (energy > ENERGY_FLOOR * ENERGY_FLOOR) best = max(best, product / sqrt(energy))
        }
        return best
    }

    /** The level of a band whose one-sided spectrum sums to [power], scaled so a sine keeps its RMS level. */
    private fun bandDb(power: Double): Double = powerDb(2 * power / (WINDOW * HANN_POWER))

    private fun powerDb(meanSquare: Double): Double = 10 * log10(meanSquare + POWER_FLOOR)

    private companion object {
        const val WINDOW = 512
        const val FULL_SCALE = 32_768.0
        const val SAMPLE_RATE = 16_000

        // Column indexes, in the order of FeatureTrack's constructor.
        const val RMS = 0
        const val PEAK = 1
        const val LOW = 2
        const val SPEECH = 3
        const val HIGH = 4
        const val FLATNESS = 5
        const val ZERO_CROSSINGS = 6
        const val VOICING = 7

        /** FFT bins are 31.25 Hz wide; the speech band is 300 to 3400 Hz. */
        const val SPEECH_FIRST_BIN = 10
        const val SPEECH_LAST_BIN = 108

        /** Pitch periods of 2.5 to 14 ms, about 70 to 400 Hz, in samples. */
        const val MIN_LAG = SAMPLE_RATE * 25 / 10_000
        const val MAX_LAG = SAMPLE_RATE * 14 / 1_000

        /** Far below the quietest sample (1 / 32 768 squared is about 1e-9), so silence stays finite. */
        const val POWER_FLOOR = 1e-14
        const val ENERGY_FLOOR = 1e-10

        val HANN = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / WINDOW) }
        val HANN_POWER = HANN.sumOf { it * it }
    }
}
