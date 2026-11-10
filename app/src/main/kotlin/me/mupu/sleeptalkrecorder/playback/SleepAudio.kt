package me.mupu.sleeptalkrecorder.playback

import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import java.io.Closeable
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

/**
 * The audio of a [SleepTimeline] as one WAV stream: a header, then the samples of every chunk file
 * the timeline plays, back to back. The player plays it as a single item, so a position in the
 * stream is the position in the sleep, down to the sample; cut playback relies on that to fade at
 * the right samples.
 *
 * The header is RF64, the WAV variant with 64-bit sizes, because a sleep of more than 37 hours
 * would not fit the 32-bit sizes of plain WAV. Every recording of the app has the microphone's
 * format (16-bit mono at the same rate), which the stream takes from the first recording.
 *
 * Reads are not thread-safe; [close] releases the open chunk file.
 */
class SleepAudio(timeline: SleepTimeline) : Closeable {

    private val chunks: List<ChunkFile> = timeline.recordings.flatMap { it.chunks }

    /** Where each chunk's samples start in the stream, in bytes; one more entry for the end. */
    private val starts: LongArray

    /** Samples per second. */
    val sampleRate: Int = sampleRateOf(timeline)

    /** The bytes of the header that comes before the samples. */
    val headerBytes: Int get() = HEADER_BYTES

    /** The bytes of the whole stream. */
    val length: Long

    private val header: ByteArray

    private var openIndex = -1
    private var openFile: RandomAccessFile? = null

    init {
        starts = LongArray(chunks.size + 1)
        starts[0] = HEADER_BYTES.toLong()
        chunks.forEachIndexed { i, chunk -> starts[i + 1] = starts[i] + chunk.frames * ChunkedWavWriter.BYTES_PER_FRAME }
        length = starts.last()
        header = header(dataBytes = length - HEADER_BYTES)
    }

    /**
     * Reads up to [length] bytes from [position] of the stream into [buffer] at [offset]; returns
     * how many were read, at least one unless [length] is zero, or -1 at the end of the stream.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (position >= this.length) return -1

        if (position < HEADER_BYTES) {
            val count = minOf(length.toLong(), HEADER_BYTES - position).toInt()
            header.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
            return count
        }

        val index = starts.indexOfLast { it <= position }.coerceAtMost(chunks.lastIndex)
        val file = open(index)
        val count = minOf(length.toLong(), starts[index + 1] - position).toInt()
        file.seek(ChunkedWavWriter.HEADER_BYTES + position - starts[index])
        // The timeline counted these bytes in the file, so a short read means it changed since.
        file.readFully(buffer, offset, count)
        return count
    }

    /**
     * The samples of [stretch] of the sleep's audio, from -1 to 1, as far as the sleep has audio
     * there.
     *
     * @throws java.io.IOException when a chunk file cannot be read.
     */
    fun clip(stretch: Stretch): FloatArray {
        val pcm = pcm(stretch)
        return FloatArray(pcm.size) { pcm[it] / PCM_SCALE }
    }

    /**
     * The 16-bit samples of [stretch] of the sleep's audio, as recorded, as far as the sleep has
     * audio there.
     *
     * @throws java.io.IOException when a chunk file cannot be read.
     */
    fun pcm(stretch: Stretch): ShortArray {
        fun byteAt(position: Duration): Long {
            val frame = position.toNanos().coerceAtLeast(0) * sampleRate / NANOS_PER_SECOND
            return (HEADER_BYTES + frame * ChunkedWavWriter.BYTES_PER_FRAME).coerceAtMost(length)
        }
        val from = byteAt(stretch.start)
        val bytes = ByteArray((byteAt(stretch.end) - from).coerceAtLeast(0).toInt())
        var done = 0
        while (done < bytes.size) done += read(from + done, bytes, done, bytes.size - done)

        val samples = ShortArray(bytes.size / ChunkedWavWriter.BYTES_PER_FRAME)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
    }

    override fun close() {
        openFile?.close()
        openFile = null
        openIndex = -1
    }

    private fun open(index: Int): RandomAccessFile {
        openFile?.takeIf { openIndex == index }?.let { return it }
        close()
        return RandomAccessFile(chunks[index].file, "r").also {
            openFile = it
            openIndex = index
        }
    }

    private fun header(dataBytes: Long): ByteArray {
        val bytesPerFrame = ChunkedWavWriter.BYTES_PER_FRAME
        val fields = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(name: String) = fields.put(name.toByteArray(Charsets.US_ASCII))

        tag("RF64")
        fields.putInt(SIZE_IN_DS64) // the RIFF size, given in the ds64 chunk
        tag("WAVE")

        tag("ds64")
        fields.putInt(DS64_BYTES)
        fields.putLong(HEADER_BYTES - RIFF_PREAMBLE_BYTES + dataBytes) // RIFF size
        fields.putLong(dataBytes)
        fields.putLong(dataBytes / bytesPerFrame) // sample count
        fields.putInt(0) // no table of further chunk sizes

        tag("fmt ")
        fields.putInt(FMT_BYTES)
        fields.putShort(1) // PCM
        fields.putShort(1) // mono
        fields.putInt(sampleRate)
        fields.putInt(sampleRate * bytesPerFrame) // byte rate
        fields.putShort(bytesPerFrame.toShort()) // block align
        fields.putShort(16) // bits per sample

        tag("data")
        fields.putInt(SIZE_IN_DS64)
        return fields.array()
    }

    companion object {
        /** The samples per second of the stream of [timeline]. */
        fun sampleRateOf(timeline: SleepTimeline): Int = timeline.recordings.firstOrNull()?.clock?.sampleRate ?: DEFAULT_SAMPLE_RATE

        private const val NANOS_PER_SECOND = 1_000_000_000L

        /** Turns 16-bit samples into the range -1 to 1. */
        private const val PCM_SCALE = 32_768f

        /** RIFF preamble (12), ds64 chunk (8 + 28), fmt chunk (8 + 16), data chunk header (8). */
        private const val HEADER_BYTES = 80
        private const val RIFF_PREAMBLE_BYTES = 8
        private const val DS64_BYTES = 28
        private const val FMT_BYTES = 16

        /** What a 32-bit size field holds when the real size is in the ds64 chunk. */
        private const val SIZE_IN_DS64 = -1

        /** The rate of a sleep without audio, whose stream holds only the header. */
        private const val DEFAULT_SAMPLE_RATE = 16_000
    }
}
