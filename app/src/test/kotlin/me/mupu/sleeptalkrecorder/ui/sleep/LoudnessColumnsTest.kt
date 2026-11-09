package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.detection.FeatureTrack
import me.mupu.sleeptalkrecorder.detection.SleepTrack
import me.mupu.sleeptalkrecorder.playback.ChunkFile
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoudnessColumnsTest {

    // Two recordings of a minute each, the second two minutes after the first started, so the
    // clock axis has a gap from 60 to 120 s; the microphone was unavailable 30 s into the second.
    private val evening = Instant.parse("2026-11-08T22:00:00Z")
    private fun recording(start: Instant) = TimelineRecording(RecordingClock(start, 16_000), listOf(ChunkFile(File("0001.wav"), 60 * 16_000L)))
    private val timeline = SleepTimeline(listOf(recording(evening), recording(evening.plusSeconds(120))))
    private val unavailable = listOf(Stretch(Duration.ofSeconds(90), Duration.ofSeconds(100)))

    // Quiet throughout, with one loud hop 30 s in, inside the only event.
    private val hops = 6_000
    private val loudness = SleepLoudness(
        track = SleepTrack.of(
            listOf(
                FeatureTrack.fromRecords(ByteArray(hops / 2 * FeatureTrack.RECORD_BYTES)) to Duration.ZERO,
                FeatureTrack.fromRecords(ByteArray(hops / 2 * FeatureTrack.RECORD_BYTES)) to Duration.ofSeconds(60),
            ),
        ),
        level = FloatArray(hops) { if (it == 1_500) -20f else -60f },
        baseline = FloatArray(hops) { -62f },
    )
    private val events = listOf(Stretch(Duration.ofSeconds(30), Duration.ofSeconds(31)))

    // One pixel per second of the clock axis.
    private val columns = LoudnessColumns.of(
        timeline, loudness, events, unavailable, TimelineWindow.whole(timeline.clockDuration),
        width = 180f, height = 100f, step = 1f, baselineStep = 10f,
    )

    @Test
    fun `the gap and the stretch without the microphone are hatched as one run each`() {
        assertArrayEquals(floatArrayOf(60f, 120f, 150f, 160f), columns.hatched, 0f)
        assertEquals(180 - 60 - 10, columns.bars)
    }

    @Test
    fun `a bar is as loud as its loudest hop and coloured by the event in it`() {
        val bar = columns.lefts.indexOfFirst { it == 30f }
        assertEquals(0, columns.events[bar])
        assertEquals(-1, columns.events[bar + 1])
        assertTrue(columns.heights[bar] > columns.heights[bar + 1])
    }

    @Test
    fun `the baseline breaks where nothing was recorded`() {
        val heightAt = { x: Float -> columns.baseline[(columns.baseline.indices step 2).first { columns.baseline[it] == x } + 1] }
        assertTrue(heightAt(50f).isFinite())
        assertTrue(heightAt(90f).isNaN())
        assertTrue(heightAt(150f).isNaN())
        assertTrue(heightAt(170f).isFinite())
    }

    @Test
    fun `the display percentiles are those of the sorted sample`() {
        val random = java.util.Random(7)
        for (size in listOf(5_000, 50_000)) {
            val values = FloatArray(size) { -90f + 60f * random.nextFloat() }
            val sample = values.filterIndexed { i, _ -> size < 10_000 || i % 7 == 0 }.sorted()
            for (share in listOf(0f, 0.01f, 0.5f, 0.9995f, 1f)) {
                assertEquals(sample[((sample.size - 1) * share).toInt()], SleepLoudness.percentile(values, share))
            }
        }
    }
}
