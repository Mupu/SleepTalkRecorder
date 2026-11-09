package me.mupu.sleeptalkrecorder.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Database access for sleeps, their recordings and their annotations. */
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

    /** The sleep started last, if any. */
    @Query("SELECT * FROM sleep ORDER BY started_at DESC, id DESC LIMIT 1")
    suspend fun latestSleep(): SleepEntity?

    /** Marks a finished sleep as recorded again, because a new recording continues it. */
    @Query("UPDATE sleep SET ended_at = NULL WHERE id = :id")
    suspend fun reopenSleep(id: Long)

    /**
     * Deletes a sleep row. Every table that holds data of a sleep or of one of its recordings
     * references it with `ON DELETE CASCADE`, so their rows go with it.
     */
    @Query("DELETE FROM sleep WHERE id = :id")
    suspend fun deleteSleep(id: Long)

    /** All sleeps, the most recently started first, with their events found by [producer] [version]. */
    @Query("$SUMMARY ORDER BY sleep.started_at DESC")
    fun sleeps(producer: String, version: String): Flow<List<SleepSummary>>

    @Query("$SUMMARY WHERE sleep.id = :id")
    fun sleep(id: Long, producer: String, version: String): Flow<SleepSummary?>

    /** The ids of finished sleeps whose events [producer] [version] has not yet found. */
    @Query(
        """
        SELECT id FROM sleep WHERE ended_at IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM annotation WHERE annotation.sleep_id = sleep.id AND type = 'SENSITIVITY'
            AND producer = :producer AND producer_version = :version
        )
        """,
    )
    suspend fun sleepsWithoutEvents(producer: String, version: String): List<Long>

    /** Adds the kinds that are not stored yet; kinds that exist keep the user's changes. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKinds(kinds: List<EventKindEntity>)

    @Insert
    suspend fun insertAnnotations(annotations: List<AnnotationEntity>)

    /** Removes the [type] annotation of [producer] that starts at [startMillis], before it is stored anew. */
    @Query(
        """
        DELETE FROM annotation WHERE sleep_id = :sleepId AND type = :type AND start_ms = :startMillis
        AND origin = :origin AND producer = :producer
        """,
    )
    suspend fun deleteResultAt(sleepId: Long, type: AnnotationType, startMillis: Long, origin: AnnotationOrigin, producer: String)

    /** The stretches of a sleep with annotations of [type], in order. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = :type ORDER BY start_ms, id")
    fun annotations(sleepId: Long, type: AnnotationType): Flow<List<AnnotationEntity>>

    /** The sensitivities the user set for stretches of a sleep, in the order they were set. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'SENSITIVITY' AND origin = 'USER' ORDER BY id")
    suspend fun userSensitivities(sleepId: Long): List<AnnotationEntity>

    /** [userSensitivities] as they change. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'SENSITIVITY' AND origin = 'USER' ORDER BY id")
    fun userSensitivityFlow(sleepId: Long): Flow<List<AnnotationEntity>>

    @Query("DELETE FROM annotation WHERE id IN (:ids)")
    suspend fun deleteAnnotations(ids: List<Long>)

    /** Removes what [producer] found in a sleep, before it stores its new results. */
    @Query("DELETE FROM annotation WHERE sleep_id = :sleepId AND origin = :origin AND producer = :producer")
    suspend fun deleteResults(sleepId: Long, origin: AnnotationOrigin, producer: String)

    /** The events of a sleep in order, with their kinds. */
    @Query(
        """
        SELECT annotation.id, start_ms, end_ms, kind_id, event_kind.name AS kind_name, event_kind.color AS kind_color
        FROM annotation JOIN event_kind ON event_kind.id = annotation.kind_id
        WHERE sleep_id = :sleepId AND type = 'EVENT'
        ORDER BY start_ms, annotation.id
        """,
    )
    fun events(sleepId: Long): Flow<List<SleepEvent>>
}

/**
 * A sleep with its recordings and events. Only the events of a finished analysis count as found:
 * the analysis writes its events and its sensitivity in one transaction.
 */
private const val SUMMARY = """
    SELECT sleep.id, sleep.started_at, sleep.ended_at,
        (SELECT COUNT(*) FROM recording WHERE recording.sleep_id = sleep.id) AS recording_count,
        (SELECT COUNT(*) FROM annotation WHERE annotation.sleep_id = sleep.id AND type = 'EVENT') AS event_count,
        (SELECT COALESCE(SUM(end_ms - start_ms), 0) FROM annotation
            WHERE annotation.sleep_id = sleep.id AND type = 'EVENT') AS event_ms,
        EXISTS (SELECT 1 FROM annotation WHERE annotation.sleep_id = sleep.id AND type = 'SENSITIVITY'
            AND producer = :producer AND producer_version = :version) AS events_found
    FROM sleep
"""
