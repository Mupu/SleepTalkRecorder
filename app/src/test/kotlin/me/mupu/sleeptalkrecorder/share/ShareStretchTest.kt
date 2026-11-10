package me.mupu.sleeptalkrecorder.share

import me.mupu.sleeptalkrecorder.playback.ChunkFile
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareStretchTest {

    private val evening = Instant.parse("2026-11-08T21:55:00Z")

    /** Recordings of [minutes] each, an hour apart. */
    private fun timeline(vararg minutes: Long) = SleepTimeline(
        minutes.mapIndexed { i, length ->
            TimelineRecording(RecordingClock(evening.plus(Duration.ofHours(i.toLong())), 16_000), listOf(ChunkFile(File("$i.wav"), length * 60 * 16_000)))
        },
    )

    private fun s(seconds: Double) = Duration.ofMillis((seconds * 1_000).toLong())
    private fun stretch(from: Double, until: Double) = Stretch(s(from), s(until))

    @Test
    fun `it starts with the lead-in and tail and can reach ten seconds around the event`() {
        val share = ShareStretch.of(stretch(60.0, 62.0), timeline(5), leadIn = s(1.5), tail = s(0.5), unavailable = emptyList())

        assertEquals(stretch(58.5, 62.5), share.selected)
        assertEquals(share.default, share.selected)
        assertEquals(stretch(50.0, 72.0), share.bounds)
    }

    @Test
    fun `it reaches over neither the sleep's audio nor a gap nor a taken microphone`() {
        // The second recording starts 5 minutes into the audio; the microphone was taken from 5:30 on.
        val share = ShareStretch.of(stretch(302.0, 325.0), timeline(5, 5), s(3.0), s(3.0), listOf(stretch(327.0, 340.0)))

        assertEquals(stretch(300.0, 327.0), share.bounds)
        assertEquals(stretch(300.0, 327.0), share.selected)
        assertEquals(stretch(0.0, 7.0), ShareStretch.of(stretch(1.0, 2.0), timeline(5), s(3.0), s(5.0), emptyList()).selected)
        assertEquals(stretch(295.0, 300.0), ShareStretch.of(stretch(298.0, 300.0), timeline(5), s(3.0), s(3.0), emptyList()).selected)
    }

    @Test
    fun `a moved stretch stays within the bounds and lasts at least half a second`() {
        val share = ShareStretch.of(stretch(60.0, 62.0), timeline(5), s(1.5), s(0.5), emptyList())

        assertEquals(stretch(55.0, 64.0), share.select(s(55.0), s(64.0)).selected)
        assertEquals(stretch(50.0, 72.0), share.select(s(10.0), s(90.0)).selected)
        assertEquals(stretch(61.0, 61.5), share.select(s(61.0), s(61.0)).selected)
        assertEquals(stretch(71.5, 72.0), share.select(s(80.0), s(80.0)).selected)
    }

    @Test
    fun `the file is named after the event's local date and time`() {
        assertEquals("SleepTalk 2026-11-08 22-55-41.m4a", shareFileName(Instant.parse("2026-11-08T21:55:41Z"), ZoneId.of("Europe/Berlin")))
    }
}
