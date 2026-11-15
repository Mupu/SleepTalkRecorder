package me.mupu.sleeptalkrecorder.playback

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow

/**
 * Multiplies 16-bit frames by a gain and, while a volume boost is on, keeps them below
 * [CEILING] with a limiter, so a cough at 8× is turned down for a moment instead of being clipped.
 *
 * The limiter follows the peak of the boosted frames with an envelope that rises within about
 * [ATTACK_SECONDS] and falls within about [RELEASE_SECONDS], and scales a frame down by as much as
 * the envelope lies above the ceiling. As the gain changes slowly against the wave, a loud steady
 * sound keeps its shape, only quieter; it does not look ahead, so the first milliseconds of a
 * sudden loud sound go above the ceiling and are clamped at full scale. All channels of a frame
 * share one envelope, so the stereo image stays put.
 *
 * Keeps state from frame to frame: one instance serves one stream on one thread, and [reset]
 * forgets the envelope where the stream continues elsewhere.
 */
class BoostLimiter(sampleRate: Int) {

    /** How far the envelope moves towards a higher peak per frame, and towards a lower one. */
    private val attack = 1 - exp(-1.0 / (ATTACK_SECONDS * sampleRate)).toFloat()
    private val release = 1 - exp(-1.0 / (RELEASE_SECONDS * sampleRate)).toFloat()

    /** The followed peak of the boosted frames, in sample units. */
    private var envelope = 0f

    /**
     * Multiplies the samples of one [frame], one per channel, by [gain] in place. While [boost]
     * is 1 or less the boost is off and the frame is only multiplied, exactly as without a
     * limiter; above, the [gain] that includes the boost is limited.
     */
    fun process(frame: ShortArray, gain: Float, boost: Float) {
        if (boost <= 1f) {
            envelope = 0f
            if (gain != 1f) for (i in frame.indices) frame[i] = (frame[i] * gain).toInt().toShort()
            return
        }

        var peak = 0f
        for (sample in frame) peak = max(peak, abs(sample * gain))
        envelope += (peak - envelope) * (if (peak > envelope) attack else release)
        val limitedGain = if (envelope > CEILING) gain * CEILING / envelope else gain
        for (i in frame.indices) {
            frame[i] = (frame[i] * limitedGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /** Forgets the followed peak, for a stream that continues somewhere else. */
    fun reset() {
        envelope = 0f
    }

    private companion object {
        /** -1 dBFS in sample units: the limiter leaves headroom for the peaks it lets through. */
        val CEILING = 32_768f * 10f.pow(-1f / 20)

        /** About how long the envelope takes to catch up with a louder sound: one millisecond. */
        const val ATTACK_SECONDS = 0.001

        /** About how long the envelope takes to let go after a loud sound, so the gain does not pump. */
        const val RELEASE_SECONDS = 0.15
    }
}
