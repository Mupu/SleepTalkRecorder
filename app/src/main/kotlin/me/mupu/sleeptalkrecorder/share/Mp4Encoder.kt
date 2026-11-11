package me.mupu.sleeptalkrecorder.share

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes shared events with Android's own encoders and muxer into MPEG-4 files: the audio as
 * AAC-LC, alone (M4A) or with a still picture as H.264 video (MP4), formats messengers and mail apps
 * take and play everywhere.
 *
 * Each track is encoded into memory first, since the muxer needs the formats of all tracks before
 * it writes the first sample; a shared stretch is short, so its samples take a few hundred kB.
 */
internal object Mp4Encoder {

    /** One encoded sample of a track: its bytes, its presentation time and the codec's flags. */
    class Sample(val data: ByteArray, val timeUs: Long, val flags: Int)

    /** An encoded track: the [format] the muxer writes into the file's header, and its samples in order. */
    class Track(val format: MediaFormat, val samples: List<Sample>)

    /**
     * Encodes [pcm], mono 16-bit samples at [sampleRate] per second, as AAC-LC.
     *
     * @throws IllegalStateException when the encoder fails, as for empty input.
     */
    fun audio(pcm: ShortArray, sampleRate: Int): Track {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var fed = 0
        return encode(codec, format) { index ->
            val buffer = checkNotNull(codec.getInputBuffer(index))
            buffer.clear()
            val count = minOf(buffer.remaining() / BYTES_PER_SAMPLE, pcm.size - fed)
            buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, fed, count)
            // The time of the buffer's first sample, which the muxer stores as is.
            val timeUs = fed * MICROS_PER_SECOND / sampleRate
            fed += count
            val done = fed == pcm.size
            codec.queueInputBuffer(index, 0, count * BYTES_PER_SAMPLE, timeUs, if (done) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            done
        }
    }

    /**
     * Encodes [picture] as an H.264 video that shows it for [lengthUs] microseconds: one frame a
     * second, enough for a still picture, the first a key frame and the others nearly empty.
     * The picture's width and height must be even.
     *
     * @throws IllegalStateException when no encoder on the phone takes the picture's size, or it fails.
     */
    fun still(picture: Bitmap, lengthUs: Long): Track {
        val width = picture.width
        val height = picture.height
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAMES_PER_SECOND)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEY_FRAME_SECONDS)
        }
        // Not every encoder takes every size; the platform's software encoder takes any even one.
        val name = checkNotNull(MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format)) {
            "no encoder for a ${width}x$height video"
        }
        val codec = MediaCodec.createByCodecName(name)

        val pixels = IntArray(width * height)
        picture.getPixels(pixels, 0, width, 0, 0, width, height)
        val frameCount = ((lengthUs + MICROS_PER_SECOND - 1) / MICROS_PER_SECOND).coerceAtLeast(1)
        var frame = 0L
        return encode(codec, format) { index ->
            val image = checkNotNull(codec.getInputImage(index))
            writeYuv(pixels, width, height, image.planes[0], image.planes[1], image.planes[2])
            val done = ++frame == frameCount
            val timeUs = (frame - 1) * MICROS_PER_SECOND / FRAMES_PER_SECOND
            // The size of a frame in the encoder's flexible YUV 4:2:0 layout, as Android's own tests give it.
            codec.queueInputBuffer(index, 0, width * height * 3 / 2, timeUs, if (done) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
            done
        }
    }

    /**
     * Writes [tracks] into [target] as one MPEG-4 file, their samples interleaved by time.
     *
     * @throws java.io.IOException when the file cannot be written.
     * @throws IllegalStateException when the muxer refuses a track.
     */
    fun write(target: File, tracks: List<Track>) {
        val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val ids = tracks.map { muxer.addTrack(it.format) }
            muxer.start()

            val next = IntArray(tracks.size)
            val info = MediaCodec.BufferInfo()
            while (true) {
                // The track whose next sample comes first; each track keeps its own order.
                val track = tracks.indices
                    .filter { next[it] < tracks[it].samples.size }
                    .minByOrNull { tracks[it].samples[next[it]].timeUs } ?: break
                val sample = tracks[track].samples[next[track]++]
                info.set(0, sample.data.size, sample.timeUs, sample.flags)
                muxer.writeSampleData(ids[track], ByteBuffer.wrap(sample.data), info)
            }
            muxer.stop()
        } finally {
            muxer.release()
        }
    }

    /**
     * Runs [codec], configured as an encoder with [format], until it gave all its output, and
     * returns that output. [queue] fills and queues the input buffer of an index and says whether
     * that was the last one.
     */
    private fun encode(codec: MediaCodec, format: MediaFormat, queue: (index: Int) -> Boolean): Track {
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val samples = mutableListOf<Sample>()
            var outputFormat: MediaFormat? = null
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) inputDone = queue(index)
                }

                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // The format carries the codec's configuration, which the muxer writes into the file's header.
                    outputFormat = codec.outputFormat
                } else if (index >= 0) {
                    val buffer = checkNotNull(codec.getOutputBuffer(index))
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!isConfig && info.size > 0) {
                        val data = ByteArray(info.size)
                        buffer.position(info.offset)
                        buffer.get(data)
                        samples += Sample(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return Track(checkNotNull(outputFormat) { "the encoder gave no format" }, samples)
        } finally {
            codec.release()
        }
    }

    /**
     * Converts [pixels], ARGB rows of [width] by [height], into the Y, U and V planes of a YUV 4:2:0
     * picture, in the limited range of BT.601 that H.264 players assume when a video names no
     * colours. Each U and V value is that of the top left pixel of its 2×2 block; the card is
     * flat colour and text, so averaging would gain nothing visible.
     */
    private fun writeYuv(pixels: IntArray, width: Int, height: Int, y: Image.Plane, u: Image.Plane, v: Image.Plane) {
        val yBuffer = y.buffer
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        for (row in 0 until height) {
            for (column in 0 until width) {
                val argb = pixels[row * width + column]
                val r = argb shr 16 and 0xff
                val g = argb shr 8 and 0xff
                val b = argb and 0xff
                yBuffer.put(row * y.rowStride + column * y.pixelStride, (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte())
                if (row % 2 == 0 && column % 2 == 0) {
                    val chromaRow = row / 2
                    val chromaColumn = column / 2
                    uBuffer.put(chromaRow * u.rowStride + chromaColumn * u.pixelStride, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                    vBuffer.put(chromaRow * v.rowStride + chromaColumn * v.pixelStride, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
                }
            }
        }
    }

    /** Plenty for speech at 16 kHz mono; a minute takes about 360 kB. */
    private const val AUDIO_BIT_RATE = 48_000

    /**
     * What the encoder may spend on the video. Only the key frames carry the picture, and a still
     * one needs a fraction of this; the frames after it repeat it in a few bytes.
     */
    private const val VIDEO_BIT_RATE = 400_000

    private const val FRAMES_PER_SECOND = 1

    /** A key frame every ten seconds, so a player that seeks finds the picture close by. */
    private const val KEY_FRAME_SECONDS = 10

    private const val BYTES_PER_SAMPLE = 2
    private const val MICROS_PER_SECOND = 1_000_000L

    /** How long one wait for a buffer of the encoder lasts, in microseconds. */
    private const val TIMEOUT_US = 10_000L
}
