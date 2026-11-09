package me.mupu.sleeptalkrecorder.detection

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The user's sensitivity for stretches of a sleep: kept as positions in its audio, so every new
 * search for events, by this detector or a changed one, finds with it again.
 */
class SensitivityOverrideTest {

    // Two recordings of 2 minutes each, the second starting 2 minutes into the sleep's audio; each
    // has a sound 4 dB above a steady floor, under the lowest automatic threshold of 5 dB.
    private val sleep = SleepTrack.of(
        listOf(
            recording(quietSoundAtSecond = 60) to Duration.ZERO,
            recording(quietSoundAtSecond = 60) to Duration.ofMinutes(2),
        ),
    )
    private val aroundSecondSound = SensitivityOverride(seconds(150), seconds(210), thresholdDb = 3f)

    @Test
    fun `an override finds events in its stretch of the sleep and nowhere else`() {
        val events = sleep.detect(listOf(aroundSecondSound)).events.map { sleep.positionOf(it.start) }

        assertEquals(listOf(seconds(180)), events)
    }

    @Test
    fun `an override still counts when the events are found again with other detector settings`() {
        val events = sleep.detect(listOf(aroundSecondSound), DetectionParams(sensitivity = 0f)).events

        assertEquals(listOf(seconds(180)), events.map { sleep.positionOf(it.start) })
        assertEquals(emptyList<HopRange>(), sleep.detect(emptyList(), DetectionParams(sensitivity = 0f)).events)
    }

    @Test
    fun `setting a stretch replaces the overrides that lie within it and keeps the others`() {
        val inside = SensitivityOverride(seconds(160), seconds(200), thresholdDb = 8f, id = 1)
        val across = SensitivityOverride(seconds(100), seconds(170), thresholdDb = 9f, id = 2)
        val elsewhere = SensitivityOverride(seconds(10), seconds(40), thresholdDb = 10f, id = 3)

        val replaced = listOf(inside, across, elsewhere).replacedBy(seconds(150), seconds(210))

        assertEquals(listOf(inside), replaced)
    }

    private fun recording(quietSoundAtSecond: Int): FeatureTrack {
        val hops = 2 * 60 * HOPS_PER_SECOND
        val random = java.util.Random(quietSoundAtSecond.toLong())
        val sound = quietSoundAtSecond * HOPS_PER_SECOND until (quietSoundAtSecond + 2) * HOPS_PER_SECOND
        val codes = ByteArray(hops) { hop ->
            FeatureTrack.levelCode(if (hop in sound) -56.0 else -60.0 + random.nextGaussian() * 0.5)
        }
        val zeros = ByteArray(hops)
        return FeatureTrack(codes, codes, zeros, codes, zeros, zeros, zeros, zeros)
    }

    private fun seconds(seconds: Int) = Duration.ofSeconds(seconds.toLong())

    private companion object {
        const val HOPS_PER_SECOND = 50
    }
}
