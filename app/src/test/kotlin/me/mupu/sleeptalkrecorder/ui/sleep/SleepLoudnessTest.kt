package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.detection.FeatureTrack
import me.mupu.sleeptalkrecorder.detection.SleepTrack
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class SleepLoudnessTest {

    // An hour of hops at -60 dB with one hop at -20 dB after 40 minutes.
    private val hops = 180_000
    private val loud = 120_000
    private val loudness = SleepLoudness(
        track = SleepTrack.of(listOf(FeatureTrack.fromRecords(ByteArray(hops * FeatureTrack.RECORD_BYTES)) to Duration.ZERO)),
        level = FloatArray(hops) { if (it == loud) -20f else -60f },
        baseline = FloatArray(hops) { -62f },
    )

    @Test
    fun `the loudest level of a stretch includes a single loud hop, at any zoom`() {
        assertEquals(-20f, loudness.loudestIn(Duration.ZERO, Duration.ofHours(1)))
        assertEquals(-20f, loudness.loudestIn(Duration.ofMinutes(39), Duration.ofMinutes(41)))
        assertEquals(-20f, loudness.loudestIn(Duration.ofMillis(2_400_000), Duration.ofMillis(2_400_020)))
        assertEquals(-60f, loudness.loudestIn(Duration.ofMinutes(41), Duration.ofMinutes(50)))
    }

    @Test
    fun `the shown range starts below the baseline and spans at least 30 dB, whatever a single click does`() {
        assertEquals(-68f, loudness.lowDb)
        assertEquals(-38f, loudness.highDb)
    }
}
