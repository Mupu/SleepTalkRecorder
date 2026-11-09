package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.detection.FeatureTrack
import me.mupu.sleeptalkrecorder.detection.SensitivityOverride
import me.mupu.sleeptalkrecorder.detection.SleepTrack
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The sensitivity sheet around an event at 60 s of a five-minute sleep with a steady floor, and a
 * quieter sound at 80 s that the automatic threshold of 5 dB misses.
 */
class SensitivityEditorTest {

    private val track = SleepTrack.of(listOf(sleep() to Duration.ZERO))
    private val detection = EventDetector.detect(track.track)
    private val window = Stretch(seconds(30), seconds(92))

    @Test
    fun `a lower threshold around the event finds the quieter sound there`() {
        val editor = SensitivityEditor(track, detection, existing = emptyList(), window, anchor = seconds(60))

        assertEquals(1, editor.events(SensitivitySetting.Unchanged).size)
        assertEquals(2, editor.events(SensitivitySetting.Threshold(3f)).size)
        assertEquals(listOf(SensitivityOverride(seconds(30), seconds(92), 3f)), editor.overrides(SensitivitySetting.Threshold(3f)))
    }

    @Test
    fun `the slider starts at the setting in force and resets to the one without the stretch's own`() {
        val sleepWide = SensitivityOverride(Duration.ZERO, seconds(300), thresholdDb = 9f, id = 1)
        val here = SensitivityOverride(seconds(30), seconds(92), thresholdDb = 3f, id = 2)
        val editor = SensitivityEditor(track, detection, existing = listOf(sleepWide, here), window, anchor = seconds(60))

        assertEquals(3f, editor.currentDb, 0f)
        assertEquals(9f, editor.inheritedDb, 0f)
        assertEquals(listOf(sleepWide), editor.overrides(SensitivitySetting.Inherited))
        assertEquals("only the loud event", 1, editor.events(SensitivitySetting.Inherited).size)
    }

    @Test
    fun `the sleep's own setting starts from the automatic threshold`() {
        val aroundAnEvent = SensitivityOverride(seconds(30), seconds(92), thresholdDb = 3f, id = 1)
        val editor = SensitivityEditor(track, detection, listOf(aroundAnEvent), Stretch(Duration.ZERO, seconds(300)), anchor = null)

        assertEquals(detection.thresholdDb, editor.currentDb, 0f)
        assertEquals(2, editor.events(SensitivitySetting.Unchanged).size)
        assertEquals(emptyList<SensitivityOverride>(), editor.overrides(SensitivitySetting.Inherited))
    }

    private fun sleep(): FeatureTrack {
        val random = java.util.Random(1)
        val codes = ByteArray(5 * 60 * 50) { hop ->
            val db = when (hop / 50) {
                in 60 until 62 -> -40.0
                in 80 until 82 -> -56.0
                else -> -60.0 + random.nextGaussian() * 0.5
            }
            FeatureTrack.levelCode(db)
        }
        val zeros = ByteArray(codes.size)
        return FeatureTrack(codes, codes, zeros, codes, zeros, zeros, zeros, zeros)
    }

    private fun seconds(seconds: Int) = Duration.ofSeconds(seconds.toLong())
}
