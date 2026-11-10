package me.mupu.sleeptalkrecorder.share

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * Encodes mono 16-bit PCM as AAC-LC in an MPEG-4 audio file (M4A) with Android's own encoder and
 * muxer, the format messengers and mail apps take and play everywhere.
 */
internal object M4aEncoder {

    /**
     * Writes [pcm], recorded at [sampleRate] samples per second, to [target] as M4A.
     *
     * @throws java.io.IOException when the file cannot be written.
     * @throws IllegalStateException when the encoder or muxer fails, as for empty input.
     */
    fun encode(pcm: ShortArray, sampleRate: Int, target: File) {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            var track = -1
            var fed = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = checkNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        val count = minOf(buffer.remaining() / BYTES_PER_SAMPLE, pcm.size - fed)
                        buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, fed, count)
                        // The time of the buffer's first sample, which the muxer stores as is.
                        val timeUs = fed * MICROS_PER_SECOND / sampleRate
                        fed += count
                        inputDone = fed == pcm.size
                        val flags = if (inputDone) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, count * BYTES_PER_SAMPLE, timeUs, flags)
                    }
                }

                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The format carries the codec's configuration, which the muxer writes into the file's header.
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (index >= 0) {
                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        check(track >= 0) { "the encoder gave data before its format" }
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            muxer.stop()
        } finally {
            codec.release()
            muxer.release()
        }
    }

    /** Plenty for speech at 16 kHz mono; a minute takes about 360 kB. */
    private const val BIT_RATE = 48_000

    private const val BYTES_PER_SAMPLE = 2
    private const val MICROS_PER_SECOND = 1_000_000L

    /** How long one wait for a buffer of the encoder lasts, in microseconds. */
    private const val TIMEOUT_US = 10_000L
}
