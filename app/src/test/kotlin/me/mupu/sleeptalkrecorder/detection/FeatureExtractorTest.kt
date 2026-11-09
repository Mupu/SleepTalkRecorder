package me.mupu.sleeptalkrecorder.detection

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureExtractorTest {

    @Test
    fun `every 320 samples make one hop, however the audio is split into buffers`() {
        var hops = 0
        val extractor = FeatureExtractor { hops++ }

        extractor.push(ShortArray(1_000), 1_000)
        assertEquals(3, hops)
        extractor.push(ShortArray(500), 280)
        assertEquals(4, hops)
    }

    @Test
    fun `a 1 kHz tone is loud in the speech band and quiet below and above it`() {
        // Amplitude 0.1 of full scale: RMS -23 dBFS, peak -20 dBFS.
        val track = extract(tone(hertz = 1_000.0, amplitude = 0.1, seconds = 1.0))
        val hop = 20

        assertEquals(-23.0, track.rmsDb(hop).toDouble(), 0.5)
        assertEquals(-20.0, track.peakDb(hop).toDouble(), 0.5)
        assertEquals(-23.0, track.speechDb(hop).toDouble(), 1.0)
        assertTrue(track.lowDb(hop) < -60)
        assertTrue(track.highDb(hop) < -60)
    }

    @Test
    fun `a 100 Hz hum lands in the low band`() {
        val track = extract(tone(hertz = 100.0, amplitude = 0.1, seconds = 1.0))

        assertEquals(-23.0, track.lowDb(20).toDouble(), 1.5)
        assertTrue(track.speechDb(20) < -50)
    }

    @Test
    fun `a 6 kHz hiss lands in the high band`() {
        val track = extract(tone(hertz = 6_000.0, amplitude = 0.1, seconds = 1.0))

        assertEquals(-23.0, track.highDb(20).toDouble(), 1.0)
        assertTrue(track.speechDb(20) < -50)
    }

    @Test
    fun `white noise is flat and unvoiced, a tone is neither`() {
        val noise = extract(noise(amplitude = 0.1, seconds = 1.0))
        val tone = extract(tone(hertz = 200.0, amplitude = 0.1, seconds = 1.0))

        assertTrue("noise flatness ${noise.flatness(20)}", noise.flatness(20) > 0.4f)
        assertTrue("noise voicing ${noise.voicing(20)}", noise.voicing(20) < 0.35f)
        assertTrue("tone flatness ${tone.flatness(20)}", tone.flatness(20) < 0.1f)
        assertTrue("tone voicing ${tone.voicing(20)}", tone.voicing(20) > 0.9f)
    }

    @Test
    fun `a 1 kHz tone crosses zero twice per period`() {
        val track = extract(tone(hertz = 1_000.0, amplitude = 0.1, seconds = 1.0))

        // 20 periods in a hop of 20 ms.
        assertEquals(40.0, track.zeroCrossings(20).toDouble(), 1.0)
    }

    @Test
    fun `digital silence is stored as the lowest level`() {
        val track = extract(ShortArray(16_000))

        assertEquals(0, track.speech[20].toInt())
        assertEquals(FeatureTrack.SILENT_DB, track.rmsDb(20))
    }

    private fun extract(samples: ShortArray): FeatureTrack {
        val records = ByteArrayOutputStream()
        FeatureExtractor { records.write(it) }.push(samples, samples.size)
        return FeatureTrack.fromRecords(records.toByteArray())
    }

    private fun tone(hertz: Double, amplitude: Double, seconds: Double) = ShortArray((seconds * RATE).toInt()) {
        (amplitude * FULL_SCALE * sin(2 * PI * hertz * it / RATE)).roundToInt().toShort()
    }

    private fun noise(amplitude: Double, seconds: Double): ShortArray {
        val random = Random(7)
        // Uniform noise of RMS amplitude/sqrt(2), like the tones.
        val spread = amplitude * FULL_SCALE * sqrt(3.0 / 2.0)
        return ShortArray((seconds * RATE).toInt()) { (random.nextDouble(-spread, spread)).roundToInt().toShort() }
    }

    private companion object {
        const val RATE = 16_000
        const val FULL_SCALE = 32_767.0
    }
}
