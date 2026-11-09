package me.mupu.sleeptalkrecorder.detection

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepTrackTest {

    // The first recording holds 1 000 samples: three hops and 40 samples (2.5 ms) without one,
    // so the second recording's first hop starts at 62.5 ms, not at 60 ms.
    private val sleep = SleepTrack.of(
        listOf(
            track(hops = 3) to Duration.ZERO,
            track(hops = 2) to Duration.ofNanos(62_500_000),
        ),
    )

    @Test
    fun `hops of a later recording start where that recording starts`() {
        assertEquals(5, sleep.track.size)
        assertEquals(Duration.ofMillis(40), sleep.positionOf(2))
        assertEquals(Duration.ofNanos(62_500_000), sleep.positionOf(3))
        assertEquals(Duration.ofNanos(102_500_000), sleep.positionOf(5))
    }

    @Test
    fun `a position maps to the hop that holds it`() {
        assertEquals(2, sleep.hopAt(Duration.ofMillis(61)))
        assertEquals(3, sleep.hopAt(Duration.ofMillis(70)))
        assertEquals(4, sleep.hopAt(Duration.ofSeconds(10)))
    }

    private fun track(hops: Int) = FeatureTrack.fromRecords(ByteArray(hops * FeatureTrack.RECORD_BYTES))
}
