package me.mupu.sleeptalkrecorder.time

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingClockTest {

    private val start = Instant.parse("2026-11-08T22:41:00Z")
    private val clock = RecordingClock(startedAt = start, sampleRate = 16_000)

    @Test
    fun `a position in samples maps to the clock time it was recorded at`() {
        val sixHoursTenMinutes = 16_000L * (6 * 3600 + 10 * 60)

        assertEquals(Instant.parse("2026-11-09T04:51:00Z"), clock.instantAt(sixHoursTenMinutes))
        assertEquals(start.plusNanos(500_000), clock.instantAt(8))
    }

    @Test
    fun `a clock time maps back to the sample recorded at it`() {
        assertEquals(16_000L * 90, clock.frameAt(Instant.parse("2026-11-08T22:42:30Z")))
        assertEquals(8L, clock.frameAt(start.plusNanos(500_000)))
    }

    @Test
    fun `a sample count is a duration`() {
        assertEquals(Duration.ofHours(8).plusMillis(250), clock.durationOf(16_000L * 8 * 3600 + 4_000))
    }

    @Test
    fun `positions far beyond a night do not overflow`() {
        val fortyEightKilohertz = RecordingClock(start, 48_000)
        val thirtyDays = 48_000L * 3600 * 24 * 30

        assertEquals(start.plus(Duration.ofDays(30)), fortyEightKilohertz.instantAt(thirtyDays))
        assertEquals(thirtyDays, fortyEightKilohertz.frameAt(start.plus(Duration.ofDays(30))))
    }

    @Test
    fun `the start is the wall-clock time the audio clock says the first sample was captured`() {
        // The audio timestamp says sample 32 000 (2 s in) was captured at 10 s since boot. Now it
        // is 12 s since boot and 22:41:12 on the wall clock, so sample 0 was captured at 22:41:08.
        val anchored = RecordingClock.anchoredAt(
            sampleRate = 16_000,
            timestampFrame = 32_000,
            timestampNanos = 10_000_000_000,
            nowElapsedNanos = 12_000_000_000,
            now = Instant.parse("2026-11-08T22:41:12Z"),
        )

        assertEquals(RecordingClock(Instant.parse("2026-11-08T22:41:08Z"), 16_000), anchored)
    }
}
