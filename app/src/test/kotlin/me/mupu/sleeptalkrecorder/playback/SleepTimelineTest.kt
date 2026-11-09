package me.mupu.sleeptalkrecorder.playback

import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTimelineTest {

    private val evening = Instant.parse("2026-11-08T22:41:00Z")
    private val clock = RecordingClock(evening, sampleRate = 16_000)

    /** A recording on [clock] whose chunk files hold [frames] samples each. */
    private fun recording(clock: RecordingClock, vararg frames: Long) =
        TimelineRecording(clock, frames.mapIndexed { i, n -> ChunkFile(File("$i.wav"), n) })

    @Test
    fun `a position in a later chunk file maps to the clock time of its samples`() {
        // Two 15-minute chunk files and a shorter third one.
        val chunk = 15 * 60 * 16_000L
        val timeline = SleepTimeline(listOf(recording(clock, chunk, chunk, 16_000L * 42)))

        assertEquals(Duration.ofMinutes(30).plusSeconds(42), timeline.duration)
        assertEquals(Duration.ofMinutes(15).plusMillis(3_250), timeline.positionOf(1, Duration.ofMillis(3_250)))
        assertEquals(Instant.parse("2026-11-08T23:11:10Z"), timeline.instantAt(Duration.ofMinutes(30).plusSeconds(10)))
    }

    @Test
    fun `the gap between two recordings exists only on the clock`() {
        // The first recording lasts 10 minutes; the second starts an hour after the first.
        val first = recording(clock, 10 * 60 * 16_000L)
        val second = recording(RecordingClock(evening.plus(Duration.ofHours(1)), 16_000), 5 * 60 * 16_000L)
        val timeline = SleepTimeline(listOf(first, second))

        assertEquals(Duration.ofMinutes(15), timeline.duration)
        assertEquals(Duration.ofMinutes(10), timeline.positionOf(1, Duration.ZERO))
        assertEquals(Instant.parse("2026-11-08T22:50:59Z"), timeline.instantAt(Duration.ofSeconds(9 * 60 + 59)))
        assertEquals(Instant.parse("2026-11-08T23:43:00Z"), timeline.instantAt(Duration.ofMinutes(12)))
    }

    @Test
    fun `a position is located in the file that holds it`() {
        val timeline = SleepTimeline(listOf(recording(clock, 16_000L * 60, 16_000L * 60)))

        assertEquals(TimelineLocation(0, Duration.ofSeconds(59)), timeline.locate(Duration.ofSeconds(59)))
        assertEquals(TimelineLocation(1, Duration.ZERO), timeline.locate(Duration.ofSeconds(60)))
        assertEquals(TimelineLocation(1, Duration.ofSeconds(30)), timeline.locate(Duration.ofSeconds(90)))
    }

    @Test
    fun `positions outside the sleep are held at its start and end`() {
        val timeline = SleepTimeline(listOf(recording(clock, 16_000L * 60)))

        assertEquals(TimelineLocation(0, Duration.ZERO), timeline.locate(Duration.ofSeconds(-5)))
        assertEquals(TimelineLocation(0, Duration.ofSeconds(60)), timeline.locate(Duration.ofMinutes(5)))
        assertEquals(evening.plusSeconds(60), timeline.instantAt(Duration.ofMinutes(5)))
    }

    @Test
    fun `a clock time is exact to the sample at a sample rate without whole milliseconds`() {
        // At 44.1 kHz a chunk of 1,000 samples lasts 22.6757... ms.
        val clock = RecordingClock(evening, sampleRate = 44_100)
        val timeline = SleepTimeline(listOf(recording(clock, 1_000, 1_000, 1_000)))

        val start = timeline.positionOf(2, Duration.ZERO)
        assertEquals(clock.durationOf(2_000), start)
        assertEquals(clock.instantAt(2_000), timeline.instantAt(start))
    }
}
