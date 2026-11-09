package me.mupu.sleeptalkrecorder.data

import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the audio of each recording lives: recording `n` in [recordingsDirectory]`/n/`, as chunk
 * WAV files next to its feature track. Every file of a recording belongs in its directory, so
 * deleting the directory removes all of them.
 */
class RecordingFiles(
    /** The parent directory of every recording's directory; excluded from backups. */
    val recordingsDirectory: File,
) {
    /** The directory of the recording with the id [recordingId]. */
    fun directoryOf(recordingId: Long) = File(recordingsDirectory, recordingId.toString())

    /** The audio of [recordings], in this order, as their files hold it now. */
    suspend fun timelineOf(recordings: List<RecordingEntity>): SleepTimeline = withContext(Dispatchers.IO) {
        SleepTimeline(
            recordings.map { recording ->
                TimelineRecording.read(
                    clock = RecordingClock(recording.startedAt, recording.sampleRate),
                    frames = recording.frames,
                    directory = directoryOf(recording.id),
                )
            },
        )
    }
}
