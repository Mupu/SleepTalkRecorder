package me.mupu.sleeptalkrecorder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Database access for sleeps and their recordings. */
@Dao
interface SleepDao {

    @Insert
    suspend fun insertSleep(sleep: SleepEntity): Long

    @Insert
    suspend fun insertRecording(recording: RecordingEntity): Long

    @Query("SELECT * FROM recording WHERE id = :id")
    suspend fun recording(id: Long): RecordingEntity?

    /** The recordings of a sleep in the order they were recorded. */
    @Query("SELECT * FROM recording WHERE sleep_id = :sleepId ORDER BY started_at, id")
    suspend fun recordingsOf(sleepId: Long): List<RecordingEntity>

    @Query("SELECT * FROM recording WHERE state = 'RECORDING'")
    suspend fun unfinishedRecordings(): List<RecordingEntity>

    @Query("UPDATE recording SET started_at = :startedAt WHERE id = :id")
    suspend fun setRecordingStart(id: Long, startedAt: Instant)

    @Query("UPDATE recording SET frames = :frames WHERE id = :id")
    suspend fun setRecordingFrames(id: Long, frames: Long)

    @Query("UPDATE recording SET frames = :frames, state = 'FINISHED' WHERE id = :id")
    suspend fun finishRecording(id: Long, frames: Long)

    /** Moves a sleep's start to the start of its earliest recording. */
    @Query(
        """
        UPDATE sleep SET started_at = (SELECT MIN(started_at) FROM recording WHERE sleep_id = :id)
        WHERE id = :id
        """,
    )
    suspend fun alignSleepStart(id: Long)

    @Query("UPDATE sleep SET ended_at = :endedAt WHERE id = :id")
    suspend fun setSleepEnd(id: Long, endedAt: Instant)

    @Query("SELECT * FROM sleep WHERE id = :id")
    suspend fun findSleep(id: Long): SleepEntity?

    /**
     * Deletes a sleep row. Every table that holds data of a sleep or of one of its recordings
     * references it with `ON DELETE CASCADE`, so their rows go with it.
     */
    @Query("DELETE FROM sleep WHERE id = :id")
    suspend fun deleteSleep(id: Long)

    /** All sleeps, the most recently started first. */
    @Query(
        """
        SELECT sleep.id, sleep.started_at, sleep.ended_at, COUNT(recording.id) AS recording_count
        FROM sleep LEFT JOIN recording ON recording.sleep_id = sleep.id
        GROUP BY sleep.id
        ORDER BY sleep.started_at DESC
        """,
    )
    fun sleeps(): Flow<List<SleepSummary>>

    @Query(
        """
        SELECT sleep.id, sleep.started_at, sleep.ended_at, COUNT(recording.id) AS recording_count
        FROM sleep LEFT JOIN recording ON recording.sleep_id = sleep.id
        WHERE sleep.id = :id
        GROUP BY sleep.id
        """,
    )
    fun sleep(id: Long): Flow<SleepSummary?>
}
