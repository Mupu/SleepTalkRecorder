package me.mupu.sleeptalkrecorder.data

import android.util.Log
import androidx.room.withTransaction
import me.mupu.sleeptalkrecorder.data.Producers.DETECTOR
import me.mupu.sleeptalkrecorder.data.Producers.RECORDER
import me.mupu.sleeptalkrecorder.data.Producers.SORTING
import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import me.mupu.sleeptalkrecorder.recording.NewRecording
import me.mupu.sleeptalkrecorder.recording.RecordingStore
import me.mupu.sleeptalkrecorder.time.RecordingClock
import me.mupu.sleeptalkrecorder.time.SleepWindows
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The rows of the recordings being written: where a recording session stores a new recording, its
 * progress and its end, and where recordings a killed process left behind are finished. A new
 * sleep is named by the [windows] the settings hold when its first recording starts.
 */
class SleepRecordings(
    private val database: SleepDatabase,
    private val files: RecordingFiles,
    private val windows: suspend () -> SleepWindows,
) : RecordingStore {

    private val dao = database.sleepDao()

    override suspend fun startRecording(startedAt: Instant, sampleRate: Int): NewRecording {
        val windows = windows()
        val zone = ZoneId.systemDefault()
        return database.withTransaction {
            val last = dao.latestSleep()
            val lastEnd = last?.endedAt
            val sleep = if (last != null && lastEnd != null && windows.continues(last.date, lastEnd, startedAt, zone)) {
                // The events are found again over the longer audio once this recording ends; the
                // positions of everything else stay, because the new audio follows the old.
                dao.reopenSleep(last.id)
                dao.deleteResults(last.id, AnnotationOrigin.DEVICE, DETECTOR.id)
                dao.deleteResults(last.id, AnnotationOrigin.DEVICE, SORTING.id)
                last
            } else {
                val new = SleepEntity(startedAt = startedAt, endedAt = null, date = windows.dateOf(startedAt, zone))
                new.copy(id = dao.insertSleep(new))
            }
            val recordingId = dao.insertRecording(
                RecordingEntity(
                    sleepId = sleep.id,
                    startedAt = startedAt,
                    sampleRate = sampleRate,
                    frames = 0,
                    state = RecordingState.RECORDING,
                ),
            )
            NewRecording(sleepId = sleep.id, recordingId = recordingId, sleepDate = sleep.date)
        }
    }

    override suspend fun anchor(recordingId: Long, startedAt: Instant) {
        database.withTransaction {
            val recording = dao.recording(recordingId) ?: return@withTransaction
            dao.setRecordingStart(recordingId, startedAt)
            dao.alignSleepStart(recording.sleepId)
        }
    }

    override suspend fun saveProgress(recordingId: Long, frames: Long) {
        dao.setRecordingFrames(recordingId, frames)
    }

    override suspend fun finish(recordingId: Long, frames: Long) {
        database.withTransaction {
            val recording = dao.recording(recordingId) ?: return@withTransaction
            dao.finishRecording(recordingId, frames)
            val end = RecordingClock(recording.startedAt, recording.sampleRate).instantAt(frames)
            dao.setSleepEnd(recording.sleepId, end)
        }
    }

    override suspend fun markMicrophoneUnavailable(recordingId: Long, fromFrame: Long, untilFrame: Long) {
        database.withTransaction {
            val recording = dao.recording(recordingId) ?: return@withTransaction
            val clock = RecordingClock(recording.startedAt, recording.sampleRate)
            // Where the recording starts in the sleep's audio: after every recording before it.
            val recordingStart = dao.recordingsOf(recording.sleepId)
                .takeWhile { it.id != recordingId }
                .fold(Duration.ZERO) { start, earlier ->
                    start + RecordingClock(earlier.startedAt, earlier.sampleRate).durationOf(earlier.frames)
                }
            val start = recordingStart + clock.durationOf(fromFrame)

            dao.deleteResultAt(recording.sleepId, AnnotationType.MICROPHONE_UNAVAILABLE, start, AnnotationOrigin.DEVICE, RECORDER.id)
            dao.insertAnnotations(
                listOf(
                    AnnotationEntity(
                        sleepId = recording.sleepId,
                        type = AnnotationType.MICROPHONE_UNAVAILABLE,
                        start = start,
                        end = recordingStart + clock.durationOf(untilFrame),
                        origin = AnnotationOrigin.DEVICE,
                        producer = RECORDER,
                    ),
                ),
            )
        }
    }

    /**
     * Repairs and finishes the recordings a process left unfinished because it was killed, crashed
     * or lost its power, so that no sleep stays "running" without a recorder. Their chunk files get
     * headers that declare all the audio they hold, which keeps the seconds after the last flush
     * that reached the storage; if the files cannot be repaired, the samples of the last flush
     * count. Call it once at process start, before a new recording can begin.
     */
    suspend fun finishInterruptedRecordings() {
        for (recording in dao.unfinishedRecordings()) {
            val frames = try {
                withContext(Dispatchers.IO) { ChunkedWavWriter.repair(files.directoryOf(recording.id), recording.sampleRate) }
            } catch (error: IOException) {
                Log.w(TAG, "Could not repair recording ${recording.id}", error)
                recording.frames
            }
            finish(recording.id, frames)
        }
    }

    private companion object {
        const val TAG = "SleepRecordings"
    }
}
