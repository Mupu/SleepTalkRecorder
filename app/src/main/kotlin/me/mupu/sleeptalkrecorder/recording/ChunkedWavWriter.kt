package me.mupu.sleeptalkrecorder.recording

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Locale

/**
 * Writes one recording as 16-bit mono PCM WAV files of [framesPerChunk] samples each, named
 * `0001.wav`, `0002.wav`, ... in [directory].
 *
 * The files are crash-safe up to the last [flush]: each flush patches the size fields of the open
 * file's header and forces it to storage, so a killed process leaves a playable file that only
 * misses the audio after that flush. Chunking bounds what one damaged file can lose and keeps
 * every file far below the 4 GiB limit of the WAV size fields.
 */
class ChunkedWavWriter(
    private val directory: File,
    private val sampleRate: Int,
    private val framesPerChunk: Long,
) : Closeable {

    /** Samples written so far, across all chunk files. */
    var frames: Long = 0
        private set

    /** Samples that are safely on storage: those before the last [flush] or finished chunk. */
    var flushedFrames: Long = 0
        private set

    private var chunkIndex = 0
    private var channel: FileChannel? = null
    private var chunkFrames = 0L
    private val buffer = ByteBuffer.allocate(BUFFER_FRAMES * BYTES_PER_FRAME).order(ByteOrder.LITTLE_ENDIAN)

    /** Appends the first [count] samples of [samples], starting new chunk files as needed. */
    fun write(samples: ShortArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val open = channel ?: startChunk()
            val room = (framesPerChunk - chunkFrames).toInt()
            val n = minOf(count - offset, room, BUFFER_FRAMES)

            buffer.clear()
            for (i in offset until offset + n) buffer.putShort(samples[i])
            buffer.flip()
            while (buffer.hasRemaining()) open.write(buffer)

            offset += n
            chunkFrames += n
            frames += n
            if (chunkFrames == framesPerChunk) finishChunk()
        }
    }

    /** Makes everything written so far durable: patches the open file's header and syncs it. */
    fun flush() {
        val open = channel ?: return
        patchHeader(open, chunkFrames)
        open.force(false)
        flushedFrames = frames
    }

    /** Finishes the open chunk file. The writer cannot be used afterwards. */
    override fun close() {
        finishChunk()
    }

    private fun startChunk(): FileChannel {
        directory.mkdirs()
        chunkIndex += 1
        chunkFrames = 0
        val file = File(directory, chunkFileName(chunkIndex))
        val open = FileChannel.open(
            file.toPath(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        // A header for an empty data chunk; patchHeader fills in the sizes as audio arrives.
        open.write(header(sampleRate, dataBytes = 0))
        channel = open
        return open
    }

    private fun finishChunk() {
        val open = channel ?: return
        channel = null
        open.use {
            patchHeader(it, chunkFrames)
            it.force(false)
        }
        flushedFrames = frames
    }

    private fun patchHeader(open: FileChannel, dataFrames: Long) {
        val dataBytes = (dataFrames * BYTES_PER_FRAME).toInt()
        open.write(littleEndianInt(36 + dataBytes), RIFF_SIZE_OFFSET)
        open.write(littleEndianInt(dataBytes), DATA_SIZE_OFFSET)
    }

    private fun littleEndianInt(value: Int): ByteBuffer =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).apply { flip() }

    companion object {
        /** Size of the header in front of the PCM data, in bytes. */
        const val HEADER_BYTES = 44

        /** Bytes per sample of 16-bit mono PCM. */
        const val BYTES_PER_FRAME = 2

        private const val RIFF_SIZE_OFFSET = 4L
        private const val DATA_SIZE_OFFSET = 40L
        private const val BUFFER_FRAMES = 4_096

        /** The file name of the chunk with the 1-based [index]. */
        fun chunkFileName(index: Int): String = String.format(Locale.ROOT, "%04d.wav", index)

        /**
         * Repairs the chunk files of a recording whose writer never closed them, because the
         * process was killed or the phone shut down, and returns the samples they hold in all.
         *
         * Each file's header is rewritten to declare every whole sample the file holds: the open
         * file's header only counts the samples of the last flush, a file can end in half a sample,
         * and a header written just before the power went may never have reached the storage. A
         * file too short for a header holds no audio and is removed. The files are read in order
         * up to the first one that is missing, as the timeline plays them.
         */
        fun repair(directory: File, sampleRate: Int): Long {
            var total = 0L
            var index = 1
            while (true) {
                val file = File(directory, chunkFileName(index))
                if (!file.isFile) break
                if (file.length() < HEADER_BYTES) {
                    file.delete()
                    break
                }
                FileChannel.open(file.toPath(), StandardOpenOption.READ, StandardOpenOption.WRITE).use { open ->
                    val dataBytes = (open.size() - HEADER_BYTES) / BYTES_PER_FRAME * BYTES_PER_FRAME
                    val expected = header(sampleRate, dataBytes.toInt())
                    val found = ByteBuffer.allocate(HEADER_BYTES)
                    while (found.hasRemaining() && open.read(found, found.position().toLong()) > 0) Unit
                    found.flip()
                    if (open.size() != HEADER_BYTES + dataBytes || found != expected) {
                        open.truncate(HEADER_BYTES + dataBytes)
                        open.write(expected, 0)
                        open.force(false)
                    }
                    total += dataBytes / BYTES_PER_FRAME
                }
                index += 1
            }
            return total
        }

        /** The header of a 16-bit mono PCM WAV file with [dataBytes] bytes of audio. */
        private fun header(sampleRate: Int, dataBytes: Int): ByteBuffer =
            ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(36 + dataBytes)
                put("WAVE".toByteArray(Charsets.US_ASCII))
                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16) // size of the fmt chunk
                putShort(1) // PCM
                putShort(1) // mono
                putInt(sampleRate)
                putInt(sampleRate * BYTES_PER_FRAME) // byte rate
                putShort(BYTES_PER_FRAME.toShort()) // block align
                putShort(16) // bits per sample
                put("data".toByteArray(Charsets.US_ASCII))
                putInt(dataBytes)
                flip()
            }
    }
}
