package me.mupu.sleeptalkrecorder.playback

import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Duration
import java.time.Instant

/** A chunk file of a recording that holds [frames] samples. */
data class ChunkFile(val file: File, val frames: Long)

/** A recording as the timeline plays it: its clock and its chunk files in order, without holes. */
data class TimelineRecording(val clock: RecordingClock, val chunks: List<ChunkFile>) {

    /** The samples the recording plays. */
    val frames: Long get() = chunks.sumOf { it.frames }

    companion object {
        /**
         * Reads the chunk files [ChunkedWavWriter] wrote into [directory] for a recording of
         * [frames] samples, the count the database knows.
         *
         * Each file plays the samples its header declares, which is what a WAV player plays, but
         * no more than the recording has left; so samples written after the last flush of a
         * killed recording are left out, together with files that hold none of the recorded
         * samples. A missing or damaged file ends the recording there, because the position of
         * every later sample depends on it.
         */
        fun read(clock: RecordingClock, frames: Long, directory: File): TimelineRecording {
            val chunks = ArrayList<ChunkFile>()
            var remaining = frames
            var index = 1
            while (remaining > 0) {
                val file = File(directory, ChunkedWavWriter.chunkFileName(index))
                val declared = declaredFrames(file) ?: break
                val playable = minOf(declared, remaining)
                if (playable == 0L) break

                chunks += ChunkFile(file, playable)
                remaining -= playable
                index += 1
            }
            return TimelineRecording(clock, chunks)
        }

        /**
         * The samples a chunk file's header declares, limited to the bytes the file holds; null
         * if the file is missing or is not a WAV file as [ChunkedWavWriter] writes it.
         */
        private fun declaredFrames(file: File): Long? = try {
            RandomAccessFile(file, "r").use { wav ->
                val header = ByteArray(ChunkedWavWriter.HEADER_BYTES)
                if (wav.read(header) < header.size) return null
                val tag = { offset: Int -> String(header, offset, 4, Charsets.US_ASCII) }
                if (tag(0) != "RIFF" || tag(8) != "WAVE" || tag(36) != "data") return null

                // The size of the data chunk in bytes, an unsigned little-endian 32-bit number.
                val dataBytes = (0 until 4).fold(0L) { size, i -> size or ((header[40 + i].toLong() and 0xFF) shl 8 * i) }
                minOf(dataBytes, wav.length() - header.size) / ChunkedWavWriter.BYTES_PER_FRAME
            }
        } catch (_: IOException) {
            null
        }
    }
}

/** The stretch of a sleep from [start] until [end] (exclusive), on whichever axis it is used. */
data class Stretch(val start: Duration, val end: Duration)

/**
 * The audio of a sleep as one continuous axis: its recordings back to back, each as its chunk
 * files in order. A position on this axis is the time into the sleep's audio, which is what the
 * player plays; the gap between two recordings exists only on the clock, so it is never played
 * and takes up no position.
 *
 * Every position maps to the instant its sample was recorded through the [RecordingClock] of the
 * recording that holds it, so clock times follow from sample counts and never drift across files.
 *
 * The timeline also has a clock axis, the time since the sleep's first sample, on which each gap
 * takes its real time, so that it can be shown. A recording that starts before the previous one
 * ended on the clock (the clock was set back) follows it there without a gap, which keeps the
 * clock axis in the order of the audio.
 */
class SleepTimeline(
    /** The recordings in playing order. */
    val recordings: List<TimelineRecording>,
) {

    /** The position of the first sample of each of [recordings]. */
    val recordingPositions: List<Duration>

    /** Every chunk file of the sleep in playing order. */
    val pieces: List<File>

    /** How long the whole sleep plays. */
    val duration: Duration

    /** Where each of [recordings] starts on the clock axis. */
    private val clockStarts: List<Duration>

    /** How long the sleep lasts on the clock, from its first sample to its last, gaps included. */
    val clockDuration: Duration

    /** The gaps between recordings on the clock axis, in order. */
    val gaps: List<Stretch>

    init {
        val placed = ArrayList<Duration>()
        var recordingStart = Duration.ZERO
        for (recording in recordings) {
            placed += recordingStart
            recordingStart += recording.clock.durationOf(recording.frames)
        }
        pieces = recordings.flatMap { recording -> recording.chunks.map { it.file } }
        recordingPositions = placed
        duration = recordingStart

        val clockStarts = ArrayList<Duration>()
        val gaps = ArrayList<Stretch>()
        var clockEnd = Duration.ZERO
        recordings.forEachIndexed { index, recording ->
            val start = maxOf(Duration.between(recordings.first().clock.startedAt, recording.clock.startedAt), clockEnd)
            if (start > clockEnd) gaps += Stretch(clockEnd, start)
            clockStarts += start
            clockEnd = start + recording.clock.durationOf(recording.frames)
        }
        this.clockStarts = clockStarts
        this.gaps = gaps
        clockDuration = clockEnd
    }

    /** Where [position] lies on the clock axis. */
    fun clockOf(position: Duration): Duration {
        if (recordings.isEmpty()) return Duration.ZERO
        val held = position.coerceIn(Duration.ZERO, duration)
        val recording = recordingAt(held)
        return clockStarts[recording] + (held - recordingPositions[recording])
    }

    /**
     * The position at [clock] on the clock axis; in a gap the start of the recording after it,
     * which is where playing from there begins.
     */
    fun positionAtClock(clock: Duration): Duration {
        if (recordings.isEmpty()) return Duration.ZERO
        val recording = clockStarts.indexOfLast { it <= clock }.coerceAtLeast(0)
        val length = recordings[recording].clock.durationOf(recordings[recording].frames)
        val into = (clock - clockStarts[recording]).coerceIn(Duration.ZERO, length)
        return recordingPositions[recording] + into
    }

    /** The instant at [clock] on the clock axis, also in a gap. */
    fun instantAtClock(clock: Duration): Instant {
        if (recordings.isEmpty()) return Instant.EPOCH
        val recording = clockStarts.indexOfLast { it <= clock }.coerceAtLeast(0)
        return recordings[recording].clock.startedAt + (clock - clockStarts[recording])
    }

    /**
     * The stretch from [from] until [until], cut back so that it neither crosses the start of a
     * recording, where a gap lies, nor reaches into a stretch in which the microphone was
     * [unavailable]: what may be played around the stretch [keep], such as an event with its
     * lead-in and tail, which itself is always kept.
     */
    fun uninterrupted(from: Duration, until: Duration, keep: Stretch, unavailable: List<Stretch>): Stretch {
        val edges = recordingPositions.map { Stretch(it, it) } + unavailable
        val start = edges.filter { it.end <= keep.start }.maxOfOrNull { it.end } ?: Duration.ZERO
        val end = edges.filter { it.start >= keep.end }.minOfOrNull { it.start } ?: duration
        return Stretch(maxOf(from, start), minOf(until, end))
    }

    /** The recording that holds [position]; on the boundary of two, the later one. */
    private fun recordingAt(position: Duration): Int = recordingPositions.indexOfLast { it <= position }.coerceAtLeast(0)

    /**
     * The instant the sample at [position] was recorded; positions outside the sleep are held at
     * its start and end.
     */
    fun instantAt(position: Duration): Instant {
        require(recordings.isNotEmpty()) { "an empty timeline has no instants" }
        val held = position.coerceIn(Duration.ZERO, duration)
        val recording = recordingAt(held)
        return recordings[recording].clock.startedAt + (held - recordingPositions[recording])
    }
}
