package me.mupu.sleeptalkrecorder.recording

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChunkedWavWriterTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun ramp(from: Int, count: Int) = ShortArray(count) { (from + it).toShort() }

    @Test
    fun `audio is split into chunk files of the given length`() {
        val writer = ChunkedWavWriter(folder.root, sampleRate = 16_000, framesPerChunk = 1_000)

        writer.write(ramp(0, 600), 600)
        writer.write(ramp(600, 600), 600)
        writer.write(ramp(1_200, 900), 900)
        writer.close()

        val files = folder.root.listFiles()!!.sortedBy { it.name }
        assertEquals(listOf("0001.wav", "0002.wav", "0003.wav"), files.map { it.name })
        assertEquals(listOf(1_000, 1_000, 100), files.map { readWav(it).samples.size })
        assertEquals((1_000 until 2_000).map(Int::toShort), readWav(files[1]).samples.toList())
        assertEquals(2_100L, writer.frames)
    }

    @Test
    fun `a flushed chunk is a valid WAV file even if the writer is never closed`() {
        val writer = ChunkedWavWriter(folder.root, sampleRate = 16_000, framesPerChunk = 1_000)

        writer.write(ramp(0, 250), 250)
        writer.flush()
        writer.write(ramp(250, 100), 100) // written after the last flush: may be lost in a crash

        val wav = readWav(File(folder.root, "0001.wav"))
        assertEquals(16_000, wav.sampleRate)
        assertEquals(250, wav.declaredFrames)
    }

    @Test
    fun `only the given number of samples of a buffer is written`() {
        val writer = ChunkedWavWriter(folder.root, sampleRate = 16_000, framesPerChunk = 1_000)

        writer.write(ramp(0, 100), 40)
        writer.close()

        assertEquals((0 until 40).map(Int::toShort), readWav(File(folder.root, "0001.wav")).samples.toList())
    }

    private class Wav(val sampleRate: Int, val declaredFrames: Int, val samples: ShortArray)

    /** Reads a 16-bit mono PCM WAV file and checks the header fields against the file. */
    private fun readWav(file: File): Wav {
        val bytes = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        fun tag(offset: Int) = String(file.readBytes(), offset, 4, Charsets.US_ASCII)

        assertEquals("RIFF", tag(0))
        assertEquals("WAVE", tag(8))
        assertEquals("fmt ", tag(12))
        assertEquals(1.toShort(), bytes.getShort(20)) // PCM
        assertEquals(1.toShort(), bytes.getShort(22)) // mono
        assertEquals(16.toShort(), bytes.getShort(34)) // bits per sample
        assertEquals("data", tag(36))

        val dataBytes = bytes.getInt(40)
        assertEquals(36 + dataBytes, bytes.getInt(4))
        assertEquals(bytes.getInt(24) * 2, bytes.getInt(28)) // byte rate

        val samples = ShortArray((file.length().toInt() - 44) / 2) { bytes.getShort(44 + it * 2) }
        return Wav(sampleRate = bytes.getInt(24), declaredFrames = dataBytes / 2, samples = samples)
    }
}
