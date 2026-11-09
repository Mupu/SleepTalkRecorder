package me.mupu.sleeptalkrecorder.data

import androidx.room.ColumnInfo
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

    /**
     * The events of a sleep in order, each with the kind and transcript of its winning transcript
     * annotation: the one over the same stretch from the user, else from a server, else from the
     * phone, the latest first; without one, the detector's kind.
     */
    @Query(
        """
        SELECT event.id, event.start_ms, event.end_ms, kind.id AS kind_id, kind.name AS kind_name,
            kind.color AS kind_color, transcript.text AS transcript, transcript.origin AS transcript_origin,
            transcript.producer AS transcript_producer
        FROM annotation AS event
        LEFT JOIN annotation AS transcript ON transcript.id = (
            SELECT id FROM annotation WHERE sleep_id = event.sleep_id AND type = 'TRANSCRIPT'
                AND start_ms = event.start_ms AND end_ms = event.end_ms
            ORDER BY CASE origin WHEN 'USER' THEN 0 WHEN 'SERVER' THEN 1 ELSE 2 END, id DESC
            LIMIT 1
        )
        JOIN event_kind AS kind ON kind.id = COALESCE(transcript.kind_id, event.kind_id)
        WHERE event.sleep_id = :sleepId AND event.type = 'EVENT'
        ORDER BY event.start_ms, event.id
        """,
    )
    fun events(sleepId: Long): Flow<List<SleepEvent>>

    /** The kinds the sleep screen offers a filter for. */
    @Query("SELECT * FROM event_kind WHERE has_filter ORDER BY id")
    fun filterKinds(): Flow<List<EventKindEntity>>

    /** The stretches of a sleep's events, in order; with [onlyPending] those without a transcript of the phone. */
    @Query(
        """
        SELECT start_ms, end_ms FROM annotation AS event WHERE sleep_id = :sleepId AND type = 'EVENT'
            AND (NOT :onlyPending OR NOT $HAS_DEVICE_TRANSCRIPT)
        ORDER BY start_ms, id
        """,
    )
    suspend fun eventStretches(sleepId: Long, onlyPending: Boolean): List<EventStretch>

    /**
     * Finished sleeps whose events [producer] [version] found and that have events without a
     * transcript of the phone, latest first.
     */
    @Query(
        """
        SELECT id FROM sleep WHERE ended_at IS NOT NULL
            AND EXISTS (SELECT 1 FROM annotation WHERE annotation.sleep_id = sleep.id AND type = 'SENSITIVITY'
                AND producer = :producer AND producer_version = :version)
            AND EXISTS (SELECT 1 FROM annotation AS event WHERE event.sleep_id = sleep.id AND event.type = 'EVENT'
                AND NOT $HAS_DEVICE_TRANSCRIPT)
        ORDER BY started_at DESC
        """,
    )
    suspend fun sleepsToTranscribe(producer: String, version: String): List<Long>

    /** How many of a sleep's events there are and how many have a transcript of the phone. */
    @Query(
        """
        SELECT COUNT(*) AS events, COALESCE(SUM($HAS_DEVICE_TRANSCRIPT), 0) AS transcribed
        FROM annotation AS event WHERE sleep_id = :sleepId AND type = 'EVENT'
        """,
    )
    fun transcriptionCount(sleepId: Long): Flow<TranscriptionCount>

    @Query("SELECT EXISTS (SELECT 1 FROM annotation WHERE sleep_id = :sleepId AND type = 'EVENT' AND start_ms = :startMillis AND end_ms = :endMillis)")
    suspend fun hasEvent(sleepId: Long, startMillis: Long, endMillis: Long): Boolean

    /** Removes the transcripts of [origin] over one stretch, before the new one is stored. */
    @Query(
        """
        DELETE FROM annotation WHERE sleep_id = :sleepId AND type = 'TRANSCRIPT' AND origin = :origin
            AND start_ms = :startMillis AND end_ms = :endMillis
        """,
    )
    suspend fun deleteTranscripts(sleepId: Long, startMillis: Long, endMillis: Long, origin: AnnotationOrigin)

    /** Removes the phone's transcripts of stretches that are no longer an event, after the events were found anew. */
    @Query(
        """
        DELETE FROM annotation WHERE sleep_id = :sleepId AND type = 'TRANSCRIPT' AND origin = 'DEVICE'
            AND NOT EXISTS (SELECT 1 FROM annotation AS event WHERE event.sleep_id = annotation.sleep_id
                AND event.type = 'EVENT' AND event.start_ms = annotation.start_ms AND event.end_ms = annotation.end_ms)
        """,
    )
    suspend fun deleteOrphanedTranscripts(sleepId: Long)

    @Query("SELECT EXISTS (SELECT 1 FROM recording WHERE state = 'RECORDING')")
    suspend fun isRecording(): Boolean
}

/** Whether the event `event` has a transcript of the phone over its stretch. */
private const val HAS_DEVICE_TRANSCRIPT = """
    EXISTS (SELECT 1 FROM annotation AS transcript WHERE transcript.sleep_id = event.sleep_id
        AND transcript.type = 'TRANSCRIPT' AND transcript.origin = 'DEVICE'
        AND transcript.start_ms = event.start_ms AND transcript.end_ms = event.end_ms)
"""

/** The stretch of an event in milliseconds of the sleep's audio. */
data class EventStretch(
    @ColumnInfo(name = "start_ms") val startMillis: Long,
    @ColumnInfo(name = "end_ms") val endMillis: Long,
)

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
