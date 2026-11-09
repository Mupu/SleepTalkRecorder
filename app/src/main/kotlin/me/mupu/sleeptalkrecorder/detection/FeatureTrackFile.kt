package me.mupu.sleeptalkrecorder.detection

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The file format of a [FeatureTrack]: a 16-byte header (the ASCII magic `STFT`, then the format
 * [VERSION], the sample rate and the samples per hop as little-endian 32-bit integers) followed by
 * one record of [FeatureTrack.RECORD_BYTES] per hop.
 *
 * The file is only ever appended to, so a killed process leaves every record before the last
 * [Writer.flush] intact; a partial record at the end is ignored when reading. A file of another
 * format version reads as no track, so a changed extractor rebuilds tracks instead of misreading
 * them.
 */
object FeatureTrackFile {

    /** The name of the track file in a recording's directory, next to its chunk files. */
    const val FILE_NAME = "features.bin"

    /** The format version; raise it whenever the extractor or the record layout changes. */
    const val VERSION = 1

    private const val MAGIC = "STFT"
    private const val HEADER_BYTES = 16
    private const val SAMPLE_RATE = 16_000

    /**
     * Appends hop records to a new track file at [file], replacing any file there; or, with
     * [keepHops], to the track file there after its first [keepHops] hops, which must be stored.
     */
    class Writer(file: File, keepHops: Int? = null) : Closeable {

        private val channel = if (keepHops == null) {
            FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        } else {
            FileChannel.open(file.toPath(), StandardOpenOption.WRITE)
        }
        private val buffer = ByteBuffer.allocate(BUFFER_HOPS * FeatureTrack.RECORD_BYTES)

        init {
            if (keepHops == null) {
                val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                header.put(MAGIC.toByteArray(Charsets.US_ASCII))
                header.putInt(VERSION).putInt(SAMPLE_RATE).putInt(FeatureTrack.HOP_SAMPLES)
                header.flip()
                writeFully(header)
            } else {
                // A partial record after the kept hops goes; the next record follows the last kept one.
                val end = HEADER_BYTES + keepHops.toLong() * FeatureTrack.RECORD_BYTES
                channel.truncate(end)
                channel.position(end)
            }
        }

        /** Appends one hop's [record]. */
        fun write(record: ByteArray) {
            if (buffer.remaining() < record.size) drain()
            buffer.put(record)
        }

        /** Writes every record so far to storage and syncs it. */
        fun flush() {
            drain()
            channel.force(false)
        }

        override fun close() {
            channel.use { flush() }
        }

        private fun drain() {
            buffer.flip()
            writeFully(buffer)
            buffer.clear()
        }

        private fun writeFully(bytes: ByteBuffer) {
            while (bytes.hasRemaining()) channel.write(bytes)
        }

        private companion object {
            /** Ten seconds of hops between writes. */
            const val BUFFER_HOPS = 500
        }
    }

    /** The track stored in [file]; null if it is missing, unreadable or of another format. */
    fun read(file: File): FeatureTrack? {
        val bytes = try {
            file.readBytes()
        } catch (_: IOException) {
            return null
        }
        if (bytes.size < HEADER_BYTES) return null

        val header = ByteBuffer.wrap(bytes, 0, HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val magic = String(bytes, 0, 4, Charsets.US_ASCII)
        header.position(4)
        val valid = magic == MAGIC &&
            header.getInt() == VERSION &&
            header.getInt() == SAMPLE_RATE &&
            header.getInt() == FeatureTrack.HOP_SAMPLES
        if (!valid) return null

        return FeatureTrack.fromRecords(bytes, offset = HEADER_BYTES)
    }
}
