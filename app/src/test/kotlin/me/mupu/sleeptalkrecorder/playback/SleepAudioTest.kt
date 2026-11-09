package me.mupu.sleeptalkrecorder.playback

import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SleepAudioTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val clock = RecordingClock(Instant.parse("2026-11-08T22:41:00Z"), sampleRate = 16_000)

    /** A recording of the samples [from] until [until], each sample holding its own number. */
    private fun recording(name: String, from: Int, until: Int, counted: Long = (until - from).toLong()): TimelineRecording {
        val directory = folder.newFolder(name)
        ChunkedWavWriter(directory, sampleRate = 16_000, framesPerChunk = 1_000).use { writer ->
            val samples = ShortArray(until - from) { (from + it).toShort() }
            writer.write(samples, samples.size)
        }
        return TimelineRecording.read(clock, counted, directory)
    }

    private fun SleepAudio.samples(fromByte: Long, count: Int): ShortArray {
        val bytes = ByteArray(count * 2)
        var read = 0
        while (read < bytes.size) read += read(fromByte + read, bytes, read, bytes.size - read)
        return ShortArray(count).also { ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
    }

    @Test
    fun `the sleep plays as one stream of all its samples in order`() {
        // The second recording was killed: only 500 of its 700 samples reached the database.
        val audio = SleepAudio(SleepTimeline(listOf(recording("a", 0, 2_100), recording("b", 2_100, 2_800, counted = 500))))

        assertEquals(audio.headerBytes + 2_600L * 2, audio.length)
        assertArrayEquals(ShortArray(20) { (990 + it).toShort() }, audio.samples(audio.headerBytes + 990L * 2, 20))
        assertArrayEquals(ShortArray(10) { (2_095 + it).toShort() }, audio.samples(audio.headerBytes + 2_095L * 2, 10))
        assertEquals(-1, audio.read(audio.length, ByteArray(4), 0, 4))
    }

    @Test
    fun `the stream starts with an RF64 header that names the format and the data size`() {
        val audio = SleepAudio(SleepTimeline(listOf(recording("a", 0, 1_500))))
        val header = ByteArray(audio.headerBytes).also { audio.read(0, it, 0, it.size) }
        val fields = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val tag = { offset: Int -> String(header, offset, 4, Charsets.US_ASCII) }

        assertEquals(listOf("RF64", "WAVE", "ds64", "fmt ", "data"), listOf(0, 8, 12, 48, 72).map(tag))
        assertEquals(3_000L, fields.getLong(28))
        assertEquals(16_000, fields.getInt(60))
    }

    @Test
    fun `a stretch of the sleep is read as samples from -1 to 1, as far as there is audio`() {
        val audio = SleepAudio(SleepTimeline(listOf(recording("a", 0, 2_100))))

        // 990 to 1,010 samples are 61.875 to 63.125 ms, across the first two chunk files.
        val clip = audio.clip(Stretch(Duration.ofNanos(61_875_000), Duration.ofNanos(63_125_000)))
        assertEquals((990 until 1_010).map { it / 32_768f }, clip.toList())
        assertEquals(100, audio.clip(Stretch(Duration.ofMillis(125), Duration.ofSeconds(5))).size)
    }
}
