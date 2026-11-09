package me.mupu.sleeptalkrecorder.detection

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The detector on synthetic speech-band levels: a noise floor of known level and spread with
 * louder stretches at known hops (50 hops per second).
 */
class EventDetectorTest {

    @Test
    fun `loud stretches above a quiet floor become events at their place`() {
        val level = floor(minutes = 5, db = -60.0, spread = 1.0)
            .burst(fromSecond = 60.0, seconds = 2.0, db = -40.0)
            .burst(fromSecond = 200.0, seconds = 4.0, db = -45.0)

        val events = EventDetector.detect(trackOf(level)).events

        assertEquals(2, events.size)
        assertEquals(60.0, events[0].start / HOPS_PER_SECOND, 0.1)
        assertEquals(62.0, events[0].end / HOPS_PER_SECOND, 0.1)
        assertEquals(200.0, events[1].start / HOPS_PER_SECOND, 0.1)
        assertEquals(204.0, events[1].end / HOPS_PER_SECOND, 0.1)
    }

    @Test
    fun `a click shorter than the minimum length is no event`() {
        val level = floor(minutes = 2, db = -60.0, spread = 1.0).burst(fromSecond = 30.0, seconds = 0.02, db = -30.0)

        assertEquals(emptyList<HopRange>(), EventDetector.detect(trackOf(level)).events)
    }

    @Test
    fun `stretches separated by a short pause make one event, by a long pause two`() {
        val level = floor(minutes = 2, db = -60.0, spread = 1.0)
            .burst(fromSecond = 20.0, seconds = 1.0, db = -40.0)
            .burst(fromSecond = 22.0, seconds = 1.0, db = -40.0)
            .burst(fromSecond = 60.0, seconds = 1.0, db = -40.0)
            .burst(fromSecond = 64.0, seconds = 1.0, db = -40.0)

        val events = EventDetector.detect(trackOf(level)).events

        assertEquals(listOf(20.0 to 23.0, 60.0 to 61.0, 64.0 to 65.0), events.map { seconds(it) })
    }

    @Test
    fun `the baseline follows a louder floor, so later sounds are found against it`() {
        // A fan switches on after 5 minutes and raises the floor by 15 dB.
        val level = floor(minutes = 5, db = -60.0, spread = 1.0) +
            floor(minutes = 5, db = -45.0, spread = 1.0, seed = 2).burst(fromSecond = 200.0, seconds = 2.0, db = -25.0)

        val detection = EventDetector.detect(trackOf(level))

        assertEquals(-45.0, detection.baseline[9 * 60 * 50].toDouble(), 1.5)
        val fanOn = detection.events.filter { it.start in 290 * 50..310 * 50 }
        assertTrue("the switch is at most a short event: $fanOn", fanOn.all { it.end - it.start < 30 * 50 })
        assertTrue(detection.events.any { seconds(it) == 500.0 to 502.0 })
    }

    @Test
    fun `a fluctuating floor raises the automatic threshold above that of a steady one`() {
        val steady = EventDetector.detect(trackOf(floor(minutes = 5, db = -50.0, spread = 0.5))).thresholdDb
        val rain = EventDetector.detect(trackOf(floor(minutes = 5, db = -50.0, spread = 3.0))).thresholdDb

        assertEquals(5f, steady, 0.01f)
        assertTrue("steady $steady, rain $rain", rain > steady + 3)
    }

    @Test
    fun `digital silence is no part of the baseline`() {
        val level = floor(minutes = 3, db = -60.0, spread = 1.0).burst(fromSecond = 60.0, seconds = 40.0, db = SILENCE)

        val detection = EventDetector.detect(trackOf(level))

        assertEquals(-60.0, detection.baseline[80 * 50].toDouble(), 1.5)
        assertEquals(emptyList<HopRange>(), detection.events)
    }

    @Test
    fun `a voice-like sound in noise is found from the recorded samples`() {
        // 30 s of noise at about -60 dBFS with a 1.5 s, 300 Hz buzz 20 dB louder at 10 s.
        val random = Random(3)
        val samples = ShortArray(30 * 16_000) { i ->
            val noise = random.nextDouble(-1.0, 1.0) * 40
            val buzz = if (i in 160_000 until 184_000) 400 * sin(2 * PI * 300 * i / 16_000) else 0.0
            (noise + buzz).roundToInt().toShort()
        }
        val records = ByteArrayOutputStream()
        FeatureExtractor { records.write(it) }.push(samples, samples.size)

        val events = EventDetector.detect(FeatureTrack.fromRecords(records.toByteArray())).events

        assertEquals(1, events.size)
        assertEquals(10.0, events[0].start / HOPS_PER_SECOND, 0.1)
        assertEquals(11.5, events[0].end / HOPS_PER_SECOND, 0.1)
    }

    /** An event's start and end in seconds, to the nearest half second. */
    private fun seconds(range: HopRange) = halfSeconds(range.start) to halfSeconds(range.end)

    private fun halfSeconds(hop: Int) = (hop / HOPS_PER_SECOND * 2).roundToInt() / 2.0

    /** A noise floor: [db] with a Gaussian spread of [spread] dB, one value per hop. */
    private fun floor(minutes: Int, db: Double, spread: Double, seed: Int = 1): DoubleArray {
        val random = java.util.Random(seed.toLong())
        return DoubleArray(minutes * 60 * 50) { db + random.nextGaussian() * spread }
    }

    private fun DoubleArray.burst(fromSecond: Double, seconds: Double, db: Double): DoubleArray = also {
        val from = (fromSecond * HOPS_PER_SECOND).roundToInt()
        fill(db, from, from + (seconds * HOPS_PER_SECOND).roundToInt())
    }

    /** A track with [speechDb] as its speech band and as its full-band level. */
    private fun trackOf(speechDb: DoubleArray): FeatureTrack {
        val codes = ByteArray(speechDb.size) { FeatureTrack.levelCode(speechDb[it]) }
        val zeros = ByteArray(speechDb.size)
        return FeatureTrack(codes, codes, zeros, codes, zeros, zeros, zeros, zeros)
    }

    private companion object {
        const val HOPS_PER_SECOND = 50.0
        const val SILENCE = -200.0
    }
}
