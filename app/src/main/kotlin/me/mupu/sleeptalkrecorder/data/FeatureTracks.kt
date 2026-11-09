package me.mupu.sleeptalkrecorder.data

import me.mupu.sleeptalkrecorder.detection.FeatureExtractor
import me.mupu.sleeptalkrecorder.detection.FeatureTrack
import me.mupu.sleeptalkrecorder.detection.FeatureTrackFile
import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * The feature track of [recording], whose files are in [directory], with exactly one hop for every
 * whole hop of samples the recording plays.
 *
 * The track is normally written while recording. When it is missing or of an older format, it is
 * extracted again from the chunk files and stored, which takes a while once but keeps every sleep
 * scannable. When it is shorter than the audio, as after a killed recording, whose audio runs up
 * to ten seconds past the track's last flush, only the hops after it are extracted and appended.
 * When even that fails, the hops that could be read are returned.
 */
internal fun loadFeatureTrack(directory: File, recording: TimelineRecording): FeatureTrack {
    val hops = (recording.frames / FeatureTrack.HOP_SAMPLES).toInt()
    val file = File(directory, FeatureTrackFile.FILE_NAME)

    val stored = FeatureTrackFile.read(file)
    if (stored != null && stored.size >= hops) return stored.take(hops)
    if (hops == 0) return FeatureTrack.fromRecords(ByteArray(0))

    return try {
        if (stored != null && stored.size > 0) {
            FeatureTrackFile.Writer(file, keepHops = stored.size).use { writer -> extractFrom(recording, stored.size, writer::write) }
        } else {
            val rebuilt = File(directory, FeatureTrackFile.FILE_NAME + ".new")
            FeatureTrackFile.Writer(rebuilt).use { writer -> extractFrom(recording, firstHop = 0, writer::write) }
            if (!rebuilt.renameTo(file)) throw IOException("cannot replace $file")
        }
        (FeatureTrackFile.read(file) ?: stored)?.take(hops) ?: FeatureTrack.fromRecords(ByteArray(0))
    } catch (_: IOException) {
        stored ?: FeatureTrack.fromRecords(ByteArray(0))
    }
}

/**
 * Feeds the samples [recording] plays from hop [firstHop] on, file by file, to a [FeatureExtractor]
 * and hands each hop's record to [onHop].
 *
 * A hop's spectrum and voicing look 512 samples back, into the hop before it, so extraction starts
 * one hop earlier and leaves that hop out: the records are then exactly those a run from the
 * first sample gives.
 */
private fun extractFrom(recording: TimelineRecording, firstHop: Int, onHop: (ByteArray) -> Unit) {
    val context = if (firstHop > 0) 1 else 0
    var left = context
    val extractor = FeatureExtractor { record -> if (left > 0) left-- else onHop(record) }

    // Samples before the first one fed, counted down through the chunk files.
    var skip = (firstHop - context).toLong() * FeatureTrack.HOP_SAMPLES
    val bytes = ByteArray(BUFFER_SAMPLES * ChunkedWavWriter.BYTES_PER_FRAME)
    val samples = ShortArray(BUFFER_SAMPLES)
    for (chunk in recording.chunks) {
        if (skip >= chunk.frames) {
            skip -= chunk.frames
            continue
        }
        BufferedInputStream(chunk.file.inputStream()).use { wav ->
            if (!skipFully(wav, ChunkedWavWriter.HEADER_BYTES + skip * ChunkedWavWriter.BYTES_PER_FRAME)) return
            var remaining = chunk.frames - skip
            skip = 0
            while (remaining > 0) {
                val wanted = minOf(remaining, BUFFER_SAMPLES.toLong()).toInt() * ChunkedWavWriter.BYTES_PER_FRAME
                val read = readUpTo(wav, bytes, wanted)
                val count = read / ChunkedWavWriter.BYTES_PER_FRAME
                if (count == 0) return
                for (i in 0 until count) {
                    // 16-bit little-endian PCM.
                    samples[i] = ((bytes[2 * i].toInt() and 0xFF) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
                }
                extractor.push(samples, count)
                remaining -= count
            }
        }
    }
}

/** Skips [count] bytes of [input]; false if it ends before. */
private fun skipFully(input: InputStream, count: Long): Boolean {
    var left = count
    while (left > 0) {
        val skipped = input.skip(left)
        if (skipped > 0) {
            left -= skipped
        } else {
            // skip() may skip nothing before the end; a read tells whether the end is reached.
            if (input.read() < 0) return false
            left--
        }
    }
    return true
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
