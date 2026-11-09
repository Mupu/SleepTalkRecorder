package me.mupu.sleeptalkrecorder.data

import androidx.room.withTransaction
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.recording.NewRecording
import me.mupu.sleeptalkrecorder.recording.RecordingStore
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Sleeps, their recordings and where the audio of each recording lives.
 *
 * The audio of recording `n` is in [recordingsDirectory]`/n/` as chunk WAV files; the database
 * holds the timeline (start instant, sample rate, sample count) that maps positions in those files
 * to clock times.
 *
 * A sleep owns its rows and its recordings' directories, and [deleteSleep] is the one place that
 * removes them: files a recording owns belong in its directory, and tables with data of a sleep or
 * a recording reference it with `ON DELETE CASCADE`.
 */
class SleepRepository(
    private val database: SleepDatabase,
    /** The parent directory of every recording's chunk files; excluded from backups. */
    val recordingsDirectory: File,
) : RecordingStore {

    private val dao = database.sleepDao()

    /** All sleeps, the most recently started first; the running one, if any, is among them. */
    fun sleeps(): Flow<List<SleepSummary>> = dao.sleeps()

    /** One sleep, or null once it no longer exists. */
    fun sleep(id: Long): Flow<SleepSummary?> = dao.sleep(id)

    /**
     * The audio of a sleep as it is stored now: its recordings in order, each with the chunk files
     * that hold its saved samples. Empty if the sleep has no playable audio.
     */
    suspend fun timeline(sleepId: Long): SleepTimeline {
        val recordings = dao.recordingsOf(sleepId)
        return withContext(Dispatchers.IO) {
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

    /** The bytes a sleep's files take up on the device. */
    suspend fun sizeOf(sleepId: Long): Long {
        val recordings = dao.recordingsOf(sleepId)
        return withContext(Dispatchers.IO) {
            recordings.sumOf { recording ->
                directoryOf(recording.id).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
            }
        }
    }

    /**
     * Deletes a finished sleep with everything it owns: the files of its recordings first, then its
     * rows, so a deletion cut short by a killed process or a file that cannot be removed never
     * leaves files that no row points to; the sleep stays listed and can be deleted again.
     *
     * Returns whether the sleep is gone. A sleep that is still recorded is not deleted.
     */
    suspend fun deleteSleep(sleepId: Long): Boolean = withContext(NonCancellable) {
        val sleep = dao.findSleep(sleepId) ?: return@withContext true
        if (sleep.endedAt == null) return@withContext false

        val directories = dao.recordingsOf(sleepId).map { directoryOf(it.id) }
        val filesGone = withContext(Dispatchers.IO) {
            // Every directory is tried, even after one failed.
            directories.map { it.deleteRecursively() }.all { it }
        }
        if (filesGone) dao.deleteSleep(sleepId)
        filesGone
    }

    override suspend fun startSleep(startedAt: Instant, sampleRate: Int): NewRecording =
        database.withTransaction {
            val sleepId = dao.insertSleep(SleepEntity(startedAt = startedAt, endedAt = null))
            val recordingId = dao.insertRecording(
                RecordingEntity(
                    sleepId = sleepId,
                    startedAt = startedAt,
                    sampleRate = sampleRate,
                    frames = 0,
                    state = RecordingState.RECORDING,
                ),
            )
            NewRecording(sleepId = sleepId, recordingId = recordingId)
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

    /**
     * Finishes recordings left unfinished by a process that ended without stopping them, with the
     * samples their last flush saved, so that no sleep stays "running" without a recorder. Call it
     * once at process start, before a new recording can begin.
     */
    suspend fun finishInterruptedRecordings() {
        for (recording in dao.unfinishedRecordings()) {
            finish(recording.id, recording.frames)
        }
    }

    private fun directoryOf(recordingId: Long) = File(recordingsDirectory, recordingId.toString())
}
