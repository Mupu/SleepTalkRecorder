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

/**
 * Where a position of a sleep lies: [offset] into the chunk file with the index [piece] in
 * [SleepTimeline.pieces].
 */
data class TimelineLocation(val piece: Int, val offset: Duration)

/**
 * The audio of a sleep as one continuous axis: its recordings back to back, each as its chunk
 * files in order. A position on this axis is the time into the sleep's audio, which is what the
 * player plays; the gap between two recordings exists only on the clock, so it is never played
 * and takes up no position.
 *
 * Every position maps to the instant its sample was recorded through the [RecordingClock] of the
 * recording that holds it, so clock times follow from sample counts and never drift across files.
 */
class SleepTimeline(
    /** The recordings in playing order. */
    val recordings: List<TimelineRecording>,
) {

    /** The position of the first sample of each of [recordings]. */
    val recordingPositions: List<Duration>

    /** Every chunk file of the sleep in playing order; the player plays each as one item. */
    val pieces: List<File>

    /** How long the whole sleep plays. */
    val duration: Duration

    /** The position of each piece's first sample. */
    private val starts: List<Duration>

    /** The position of the first sample of each piece's recording, and that recording's clock. */
    private val recordingStarts: List<Duration>
    private val clocks: List<RecordingClock>

    init {
        val pieces = ArrayList<File>()
        val starts = ArrayList<Duration>()
        val recordingStarts = ArrayList<Duration>()
        val clocks = ArrayList<RecordingClock>()
        val placed = ArrayList<Duration>()
        var recordingStart = Duration.ZERO
        for (recording in recordings) {
            placed += recordingStart
            var firstFrame = 0L
            for (chunk in recording.chunks) {
                pieces += chunk.file
                starts += recordingStart + recording.clock.durationOf(firstFrame)
                recordingStarts += recordingStart
                clocks += recording.clock
                firstFrame += chunk.frames
            }
            recordingStart += recording.clock.durationOf(firstFrame)
        }
        this.pieces = pieces
        this.starts = starts
        this.recordingStarts = recordingStarts
        this.clocks = clocks
        recordingPositions = placed
        duration = recordingStart
    }

    /** The position [offset] into the piece with the index [piece]. */
    fun positionOf(piece: Int, offset: Duration): Duration = starts[piece] + offset

    /**
     * The piece that plays [position], and how far into it. A position on the boundary of two
     * pieces lies at the start of the later one; positions outside the sleep are held at its
     * start and end.
     */
    fun locate(position: Duration): TimelineLocation {
        require(pieces.isNotEmpty()) { "an empty timeline has no positions" }
        val held = position.coerceIn(Duration.ZERO, duration)
        val piece = starts.indexOfLast { it <= held }.coerceAtLeast(0)
        return TimelineLocation(piece, held - starts[piece])
    }

    /** The instant the sample at [position] was recorded. */
    fun instantAt(position: Duration): Instant {
        val piece = locate(position).piece
        val intoRecording = position.coerceIn(Duration.ZERO, duration) - recordingStarts[piece]
        return clocks[piece].startedAt + intoRecording
    }
}
