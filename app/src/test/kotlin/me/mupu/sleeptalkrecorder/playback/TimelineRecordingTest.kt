package me.mupu.sleeptalkrecorder.playback

import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimelineRecordingTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val clock = RecordingClock(Instant.parse("2026-11-08T22:41:00Z"), sampleRate = 16_000)

    private fun record(frames: Int, framesPerChunk: Long = 1_000): ChunkedWavWriter =
        ChunkedWavWriter(folder.root, sampleRate = 16_000, framesPerChunk = framesPerChunk).apply {
            write(ShortArray(frames), frames)
        }

    private fun read(frames: Long) = TimelineRecording.read(clock, frames, folder.root).chunks

    @Test
    fun `the chunk files the writer wrote are read in order with their sample counts`() {
        record(2_100).close()

        val chunk = { name: String, frames: Long -> ChunkFile(File(folder.root, name), frames) }
        assertEquals(listOf(chunk("0001.wav", 1_000), chunk("0002.wav", 1_000), chunk("0003.wav", 100)), read(2_100))
    }

    @Test
    fun `a recording that was killed plays only up to its last flush`() {
        // The database knows the 1,300 samples of the last flush; 200 more never reached it.
        record(1_300).apply { flush() }.write(ShortArray(200), 200)

        assertEquals(listOf(1_000L, 300L), read(1_300).map { it.frames })
    }

    @Test
    fun `a chunk file started after the last flush is left out`() {
        // Killed right after the second file was opened: its header still says it is empty.
        record(1_010)

        assertEquals(listOf(1_000L), read(1_000).map { it.frames })
    }

    @Test
    fun `a missing or damaged chunk file ends the recording there`() {
        record(3_000).close()
        File(folder.root, "0002.wav").writeText("not a wave file")

        assertEquals(listOf(1_000L), read(3_000).map { it.frames })
        File(folder.root, "0001.wav").delete()
        assertEquals(emptyList<ChunkFile>(), read(3_000))
    }
}
