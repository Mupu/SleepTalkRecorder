package me.mupu.sleeptalkrecorder.recording

import java.io.Closeable

/**
 * A live stream of 16-bit mono PCM, such as the microphone. Reads block until audio is
 * available, so they run off the main thread.
 */
interface PcmSource : Closeable {

    /** Samples per second. */
    val sampleRate: Int

    /** Starts capturing; false when the source cannot deliver audio. */
    fun start(): Boolean

    /**
     * Fills [buffer] from its start and returns the number of samples read, 0 when none were
     * available yet, or a negative number when the source failed and will not deliver again.
     */
    fun read(buffer: ShortArray): Int

    /** When a captured sample was recorded on the elapsed-since-boot clock, once the source knows. */
    fun timestamp(): AudioTimestampSample?

    /**
     * Whether the system currently gives this source silence instead of audio, because a call or
     * another app holds the microphone or the user switched it off.
     */
    fun isSilenced(): Boolean
}

/** Sample [framePosition] of a capture was recorded at [nanoTime] on the elapsed-since-boot clock. */
data class AudioTimestampSample(val framePosition: Long, val nanoTime: Long)
