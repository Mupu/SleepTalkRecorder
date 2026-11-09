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

    @Test
    fun `a track cut short is completed from the audio after it, as a full extraction would be`() {
        // 30 whole hops over three chunk files; the stored track ends after 11 of them.
        val recording = record(samples = 9_700, chunkSamples = 4_000)
        val full = loadFeatureTrack(folder.newFolder(), record(samples = 9_700, chunkSamples = 4_000, into = folder.newFolder()))
        val file = File(folder.root, FeatureTrackFile.FILE_NAME)
        FeatureTrackFile.Writer(file).use { writer ->
            for (hop in 0 until 11) writer.write(ByteArray(8) { feature -> if (feature == 0) 1 else full.speech[hop] })
        }

        val track = loadFeatureTrack(folder.root, recording)

        // The stored hops are kept as they are (their first column marks them), the others extracted.
        assertEquals(30, track.size)
        assertEquals(List(11) { 1.toByte() }, track.rms.take(11))
        assertArrayEquals(full.rms.copyOfRange(11, 30), track.rms.copyOfRange(11, 30))
        assertArrayEquals(full.voicing.copyOfRange(11, 30), track.voicing.copyOfRange(11, 30))
        assertEquals(30, FeatureTrackFile.read(file)!!.size)
    }

    private fun record(samples: Int, chunkSamples: Long, into: File = folder.root): TimelineRecording {
        val clock = RecordingClock(Instant.EPOCH, 16_000)
        ChunkedWavWriter(into, 16_000, chunkSamples).use { wav ->
            wav.write(ShortArray(samples) { (it % 50 * 200 + it / 7 % 13 * 300).toShort() }, samples)
        }
        return TimelineRecording.read(clock, samples.toLong(), into)
    }
}
