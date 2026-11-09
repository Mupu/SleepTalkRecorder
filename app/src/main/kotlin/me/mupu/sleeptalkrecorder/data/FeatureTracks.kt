package me.mupu.sleeptalkrecorder.data

import me.mupu.sleeptalkrecorder.detection.FeatureTrack
import me.mupu.sleeptalkrecorder.detection.FeatureTrackFile
import me.mupu.sleeptalkrecorder.detection.FeatureTrackRecorder
import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException

/**
 * The feature track of [recording], whose files are in [directory], with exactly one hop for every
 * whole hop of samples the recording plays.
 *
 * The track is normally written while recording. When it is missing, of an older format, or shorter
 * than the audio (the storage failed mid-recording), it is extracted again from the chunk files and
 * stored, which takes a while once but keeps every sleep scannable. When even that fails, the
 * hops that could be read are returned.
 */
internal fun loadFeatureTrack(directory: File, recording: TimelineRecording): FeatureTrack {
    val hops = (recording.frames / FeatureTrack.HOP_SAMPLES).toInt()
    val file = File(directory, FeatureTrackFile.FILE_NAME)

    val stored = FeatureTrackFile.read(file)
    if (stored != null && stored.size >= hops) return stored.take(hops)
    if (hops == 0) return FeatureTrack.fromRecords(ByteArray(0))

    return try {
        val rebuilt = File(directory, FeatureTrackFile.FILE_NAME + ".new")
        FeatureTrackRecorder(rebuilt).use { features -> extractFrom(recording, features) }
        if (!rebuilt.renameTo(file)) throw IOException("cannot replace $file")
        (FeatureTrackFile.read(file) ?: stored)?.take(hops) ?: FeatureTrack.fromRecords(ByteArray(0))
    } catch (_: IOException) {
        stored ?: FeatureTrack.fromRecords(ByteArray(0))
    }
}

/** Feeds the samples [recording] plays, file by file, to [features]. */
private fun extractFrom(recording: TimelineRecording, features: FeatureTrackRecorder) {
    val bytes = ByteArray(BUFFER_SAMPLES * ChunkedWavWriter.BYTES_PER_FRAME)
    val samples = ShortArray(BUFFER_SAMPLES)
    for (chunk in recording.chunks) {
        BufferedInputStream(chunk.file.inputStream()).use { wav ->
            if (wav.skip(ChunkedWavWriter.HEADER_BYTES.toLong()) < ChunkedWavWriter.HEADER_BYTES) return
            var remaining = chunk.frames
            while (remaining > 0) {
                val wanted = minOf(remaining, BUFFER_SAMPLES.toLong()).toInt() * ChunkedWavWriter.BYTES_PER_FRAME
                val read = readUpTo(wav, bytes, wanted)
                val count = read / ChunkedWavWriter.BYTES_PER_FRAME
                if (count == 0) return
                for (i in 0 until count) {
                    // 16-bit little-endian PCM.
                    samples[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                }
                features.push(samples, count)
                remaining -= count
            }
        }
    }
}

/** Reads up to [count] bytes into [bytes]; fewer only at the end of the stream. */
private fun readUpTo(input: BufferedInputStream, bytes: ByteArray, count: Int): Int {
    var total = 0
    while (total < count) {
        val read = input.read(bytes, total, count - total)
        if (read < 0) break
        total += read
    }
    return total
}

private const val BUFFER_SAMPLES = 16_000
