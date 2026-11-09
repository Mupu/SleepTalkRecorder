package me.mupu.sleeptalkrecorder.data

import me.mupu.sleeptalkrecorder.detection.FeatureTrackFile
import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.time.Instant
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FeatureTracksTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `a missing track is extracted again from the audio and kept`() {
        // Two chunk files of 1 000 samples: six whole hops of 320 samples.
        val recording = record(samples = 2_000, chunkSamples = 1_000)

        val track = loadFeatureTrack(folder.root, recording)

        assertEquals(6, track.size)
        assertArrayEquals(track.speech, FeatureTrackFile.read(File(folder.root, FeatureTrackFile.FILE_NAME))!!.speech)
    }

    @Test
    fun `a track longer than the saved audio is cut to it`() {
        val recording = record(samples = 2_000, chunkSamples = 1_000)
        FeatureTrackFile.Writer(File(folder.root, FeatureTrackFile.FILE_NAME)).use { writer ->
            repeat(9) { writer.write(ByteArray(8) { 1 }) }
        }

        val track = loadFeatureTrack(folder.root, recording)

        assertEquals(6, track.size)
        assertEquals(1, track.speech[0].toInt())
    }

    private fun record(samples: Int, chunkSamples: Long): TimelineRecording {
        val clock = RecordingClock(Instant.EPOCH, 16_000)
        ChunkedWavWriter(folder.root, 16_000, chunkSamples).use { wav ->
            wav.write(ShortArray(samples) { (it % 50 * 200).toShort() }, samples)
        }
        return TimelineRecording.read(clock, samples.toLong(), folder.root)
    }
}
