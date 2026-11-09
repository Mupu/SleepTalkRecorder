package me.mupu.sleeptalkrecorder.recording

import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WavRepairTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val clock = RecordingClock(Instant.parse("2026-11-08T22:41:00Z"), sampleRate = 16_000)

    private fun ramp(count: Int) = ShortArray(count) { it.toShort() }

    /** Writes [frames] samples in chunks of 1,000 and flushes after [flushedFrames], then "crashes": the writer is never closed. */
    private fun crashAfter(frames: Int, flushedFrames: Int) {
        val writer = ChunkedWavWriter(folder.root, sampleRate = 16_000, framesPerChunk = 1_000)
        writer.write(ramp(flushedFrames), flushedFrames)
        writer.flush()
        writer.write(ramp(frames - flushedFrames), frames - flushedFrames)
    }

    private fun file(name: String) = File(folder.root, name)

    /** What a WAV player sees: the sample count each chunk file's header declares, when its header is valid. */
    private fun playable(frames: Long) = TimelineRecording.read(clock, frames, folder.root).chunks.map { it.frames }

    @Test
    fun `a killed recording keeps the samples written after its last flush`() {
        // The header of the open file still says 300 samples; 200 more reached the file.
        crashAfter(frames = 1_500, flushedFrames = 1_300)

        val frames = ChunkedWavWriter.repair(folder.root, sampleRate = 16_000)

        assertEquals(1_500L, frames)
        assertEquals(listOf(1_000L, 500L), playable(frames))
    }

    @Test
    fun `a truncated file ending in half a sample is cut to its whole samples`() {
        crashAfter(frames = 1_500, flushedFrames = 1_500)
        // A dead battery cut the file 3 bytes before its end: 498.5 samples are left.
        RandomAccessFile(file("0002.wav"), "rw").use { it.setLength(it.length() - 3) }

        val frames = ChunkedWavWriter.repair(folder.root, sampleRate = 16_000)

        assertEquals(1_498L, frames)
        assertEquals(listOf(1_000L, 498L), playable(frames))
        assertEquals(44L + 498 * 2, file("0002.wav").length())
    }

    @Test
    fun `a file whose header never reached the storage gets a new one`() {
        crashAfter(frames = 1_400, flushedFrames = 1_000)
        RandomAccessFile(file("0002.wav"), "rw").use { it.write(ByteArray(44)) }

        val frames = ChunkedWavWriter.repair(folder.root, sampleRate = 16_000)

        assertEquals(1_400L, frames)
        assertEquals(listOf(1_000L, 400L), playable(frames))
    }

    @Test
    fun `a file cut off inside its header holds no audio and is removed`() {
        crashAfter(frames = 1_000, flushedFrames = 1_000)
        file("0002.wav").writeBytes(ByteArray(20))

        val frames = ChunkedWavWriter.repair(folder.root, sampleRate = 16_000)

        assertEquals(1_000L, frames)
        assertEquals(false, file("0002.wav").exists())
    }

    @Test
    fun `a recording without files has no samples`() {
        assertEquals(0L, ChunkedWavWriter.repair(File(folder.root, "missing"), sampleRate = 16_000))
    }
}
