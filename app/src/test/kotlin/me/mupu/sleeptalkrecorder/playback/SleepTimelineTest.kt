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

    private fun minutes(minutes: Long) = Duration.ofMinutes(minutes)

    /** Recordings of 10 and 5 minutes; the second starts an hour after the first. */
    private val interrupted = SleepTimeline(
        listOf(
            recording(clock, 10 * 60 * 16_000L),
            recording(RecordingClock(evening.plus(Duration.ofHours(1)), 16_000), 5 * 60 * 16_000L),
        ),
    )

    @Test
    fun `on the clock axis the gap between two recordings takes its real time`() {
        assertEquals(minutes(65), interrupted.clockDuration)
        assertEquals(listOf(Stretch(minutes(10), minutes(60))), interrupted.gaps)
        assertEquals(minutes(62), interrupted.clockOf(minutes(12)))
        assertEquals(minutes(12), interrupted.positionAtClock(minutes(62)))
        assertEquals(Instant.parse("2026-11-08T23:11:00Z"), interrupted.instantAtClock(minutes(30)))
    }

    @Test
    fun `a moment in a gap plays from the start of the next recording`() {
        assertEquals(minutes(10), interrupted.positionAtClock(minutes(30)))
    }

    @Test
    fun `a recording that starts before the previous one ended on the clock follows it without a gap`() {
        // The clock was set back between the two recordings.
        val second = recording(RecordingClock(evening.plus(minutes(5)), 16_000), 5 * 60 * 16_000L)
        val timeline = SleepTimeline(listOf(recording(clock, 10 * 60 * 16_000L), second))

        assertEquals(minutes(15), timeline.clockDuration)
        assertEquals(emptyList<Stretch>(), timeline.gaps)
    }

    @Test
    fun `the lead-in and tail around an event stop at the edges of its recording`() {
        val event = Stretch(minutes(10).plusSeconds(1), minutes(10).plusSeconds(3))

        assertEquals(
            Stretch(minutes(10), event.end.plusSeconds(1)),
            interrupted.uninterrupted(event.start.minusSeconds(2), event.end.plusSeconds(1), event, unavailable = emptyList()),
        )
        val before = Stretch(minutes(9).plusSeconds(50), minutes(9).plusSeconds(59))
        assertEquals(
            Stretch(before.start.minusSeconds(2), minutes(10)),
            interrupted.uninterrupted(before.start.minusSeconds(2), before.end.plusSeconds(2), before, unavailable = emptyList()),
        )
    }

    @Test
    fun `the lead-in and tail around an event stop where the microphone was unavailable`() {
        val unavailable = listOf(Stretch(minutes(5), minutes(6)))
        val after = Stretch(minutes(6).plusSeconds(1), minutes(6).plusSeconds(2))
        val before = Stretch(minutes(4).plusSeconds(58), minutes(4).plusSeconds(59))

        fun around(event: Stretch) = interrupted.uninterrupted(event.start.minusSeconds(2), event.end.plusSeconds(2), event, unavailable)

        assertEquals(Stretch(minutes(6), after.end.plusSeconds(2)), around(after))
        assertEquals(Stretch(before.start.minusSeconds(2), minutes(5)), around(before))
    }
}
