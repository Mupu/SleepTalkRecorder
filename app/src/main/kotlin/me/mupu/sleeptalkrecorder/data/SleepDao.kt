package me.mupu.sleeptalkrecorder.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.Flow

/** Database access for sleeps, their recordings and their annotations. */
@Dao
interface SleepDao {

    /** Stores a new sleep and returns its id. */
    @Insert
    suspend fun insertSleep(sleep: SleepEntity): Long

    /** Stores a new recording and returns its id. */
    @Insert
    suspend fun insertRecording(recording: RecordingEntity): Long

    /** The recording with [id], or null if there is none. */
    @Query("SELECT * FROM recording WHERE id = :id")
    suspend fun recording(id: Long): RecordingEntity?

    /** The recordings of a sleep in the order they were recorded. */
    @Query("SELECT * FROM recording WHERE sleep_id = :sleepId ORDER BY started_at, id")
    suspend fun recordingsOf(sleepId: Long): List<RecordingEntity>

    /** The recordings still marked as being written: the running one, or those a killed process left. */
    @Query("SELECT * FROM recording WHERE state = 'RECORDING'")
    suspend fun unfinishedRecordings(): List<RecordingEntity>

    /** Sets the instant of a recording's first sample, once the audio clock has told it. */
    @Query("UPDATE recording SET started_at = :startedAt WHERE id = :id")
    suspend fun setRecordingStart(id: Long, startedAt: Instant)

    /** Sets how many samples of a recording are safely written. */
    @Query("UPDATE recording SET frames = :frames WHERE id = :id")
    suspend fun setRecordingFrames(id: Long, frames: Long)

    /** Marks a recording as finished with [frames] samples. */
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

    /** Sets the instant after a sleep's last sample, which marks it as finished. */
    @Query("UPDATE sleep SET ended_at = :endedAt WHERE id = :id")
    suspend fun setSleepEnd(id: Long, endedAt: Instant)

    /** The sleep with [id], or null if there is none. */
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
    fun sleeps(producer: String, version: String?): Flow<List<SleepSummary>>

    /** One sleep, like [sleeps] lists it; null once it no longer exists. */
    @Query("$SUMMARY WHERE sleep.id = :id")
    fun sleep(id: Long, producer: String, version: String?): Flow<SleepSummary?>

    /** The ids of finished sleeps whose events [producer] [version] has not yet found. */
    @Query(
        """
        SELECT id FROM sleep WHERE ended_at IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM annotation WHERE annotation.sleep_id = sleep.id AND type = 'SENSITIVITY'
            AND producer = :producer AND producer_version = :version
        )
        """,
    )
    suspend fun sleepsWithoutEvents(producer: String, version: String?): List<Long>

    /** Adds the kinds that are not stored yet; kinds that exist keep the user's changes. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertKinds(kinds: List<EventKindEntity>)

    /** Stores new annotations. */
    @Insert
    suspend fun insertAnnotations(annotations: List<AnnotationEntity>)

    /** Stores changed annotations under their ids. */
    @Update
    suspend fun updateAnnotations(annotations: List<AnnotationEntity>)

    /** The phone's transcripts that another version than [version] of its speech recognition made. */
    @Query("SELECT * FROM annotation WHERE type = 'TRANSCRIPT' AND origin = 'DEVICE' AND producer_version IS NOT :version")
    suspend fun deviceTranscriptsNotOf(version: String): List<AnnotationEntity>

    /** Removes the [type] annotation of the producer [producerId] that starts at [start], before it is stored anew. */
    @Query(
        """
        DELETE FROM annotation WHERE sleep_id = :sleepId AND type = :type AND start_ms = :start
        AND origin = :origin AND producer = :producerId
        """,
    )
    suspend fun deleteResultAt(sleepId: Long, type: AnnotationType, start: Duration, origin: AnnotationOrigin, producerId: String)

    /** The stretches of a sleep with annotations of [type], in order. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = :type ORDER BY start_ms, id")
    fun annotations(sleepId: Long, type: AnnotationType): Flow<List<AnnotationEntity>>

    /** The sensitivities the user set for stretches of a sleep, in the order they were set. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'SENSITIVITY' AND origin = 'USER' ORDER BY id")
    suspend fun userSensitivities(sleepId: Long): List<AnnotationEntity>

    /** [userSensitivities] as they change. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'SENSITIVITY' AND origin = 'USER' ORDER BY id")
    fun userSensitivityFlow(sleepId: Long): Flow<List<AnnotationEntity>>

    /** Removes the annotations with the [ids]. */
    @Query("DELETE FROM annotation WHERE id IN (:ids)")
    suspend fun deleteAnnotations(ids: List<Long>)

    /** Removes what the producer [producerId] found in a sleep, in any version, before it stores its new results. */
    @Query("DELETE FROM annotation WHERE sleep_id = :sleepId AND origin = :origin AND producer = :producerId")
    suspend fun deleteResults(sleepId: Long, origin: AnnotationOrigin, producerId: String)

    /**
     * The events of a sleep in order, with the detector's kind and without their transcripts,
     * which [withTranscripts] adds. An event is hidden while one of the user's hidden marks
     * overlaps it.
     */
    @Query(
        """
        SELECT event.id, event.start_ms, event.end_ms, kind.id AS kind_id, kind.name AS kind_name,
            kind.color AS kind_color, NULL AS transcript, NULL AS transcript_origin,
            NULL AS transcript_producer, $IS_HIDDEN AS hidden, $TAG_IDS AS tag_ids, $EVENT_NAME AS name
        FROM annotation AS event
        JOIN event_kind AS kind ON kind.id = event.kind_id
        WHERE event.sleep_id = :sleepId AND event.type = 'EVENT'
        ORDER BY event.start_ms, event.id
        """,
    )
    fun events(sleepId: Long): Flow<List<SleepEvent>>

    /**
     * The events the second way found in a sleep, in order, like [events] gives the first way's,
     * each with its human score and how it was sorted ([Sorting.decode]).
     */
    @Query(
        """
        SELECT event.id, event.start_ms, event.end_ms, kind.id AS kind_id, kind.name AS kind_name,
            kind.color AS kind_color, NULL AS transcript, NULL AS transcript_origin,
            NULL AS transcript_producer, $IS_HIDDEN AS hidden, $TAG_IDS AS tag_ids, $EVENT_NAME AS name,
            event.value AS score, event.text AS sorting
        FROM annotation AS event
        JOIN event_kind AS kind ON kind.id = event.kind_id
        WHERE event.sleep_id = :sleepId AND event.type = 'CANDIDATE'
        ORDER BY event.start_ms, event.id
        """,
    )
    fun candidates(sleepId: Long): Flow<List<CandidateRow>>

    /** The stretches of the second way's events of a sleep, in order. */
    @Query("SELECT start_ms AS start, end_ms AS `end` FROM annotation WHERE sleep_id = :sleepId AND type = 'CANDIDATE' ORDER BY start_ms, id")
    suspend fun candidateStretches(sleepId: Long): List<Stretch>

    /** The stretches of the first way's events of a sleep that have a transcript of the phone, in order. */
    @Query(
        """
        SELECT start_ms AS start, end_ms AS `end` FROM annotation AS event WHERE sleep_id = :sleepId AND type = 'EVENT'
            AND $HAS_DEVICE_TRANSCRIPT
        ORDER BY start_ms, id
        """,
    )
    suspend fun transcribedEventStretches(sleepId: Long): List<Stretch>

    /** The mark of the second way's search of a sleep by [producer] ([AnnotationType.SEARCH]); null until it began. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'SEARCH' AND producer = :producer ORDER BY id DESC LIMIT 1")
    suspend fun searchMark(sleepId: Long, producer: String): AnnotationEntity?

    /** [searchMark] as it changes. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'SEARCH' AND producer = :producer ORDER BY id DESC LIMIT 1")
    fun searchMarkFlow(sleepId: Long, producer: String): Flow<AnnotationEntity?>

    /** The ids of finished sleeps the second way, [producer] [version], has not searched to their end, the latest first. */
    @Query(
        """
        SELECT id FROM sleep WHERE ended_at IS NOT NULL AND NOT EXISTS (
            SELECT 1 FROM annotation WHERE annotation.sleep_id = sleep.id AND type = 'SEARCH'
            AND producer = :producer AND producer_version = :version AND value = 1
        )
        ORDER BY started_at DESC
        """,
    )
    suspend fun sleepsNotSearched(producer: String, version: String?): List<Long>

    /** The ids of finished sleeps the second way, [producer] [version], searched to their end, the latest first. */
    @Query(
        """
        SELECT id FROM sleep WHERE ended_at IS NOT NULL AND EXISTS (
            SELECT 1 FROM annotation WHERE annotation.sleep_id = sleep.id AND type = 'SEARCH'
            AND producer = :producer AND producer_version = :version AND value = 1
        )
        ORDER BY started_at DESC
        """,
    )
    suspend fun sleepsSearched(producer: String, version: String?): List<Long>

    /** The phone's transcripts of the second way's events of a sleep ([AnnotationType.CANDIDATE_TRANSCRIPT]). */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'CANDIDATE_TRANSCRIPT' ORDER BY id")
    suspend fun candidateTranscripts(sleepId: Long): List<AnnotationEntity>

    /** [candidateTranscripts] as they change. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'CANDIDATE_TRANSCRIPT' ORDER BY id")
    fun candidateTranscriptFlow(sleepId: Long): Flow<List<AnnotationEntity>>

    /** Removes the transcripts of the second way's events by [producerId] over exactly one stretch, before a new one is stored. */
    @Query(
        """
        DELETE FROM annotation WHERE sleep_id = :sleepId AND type = 'CANDIDATE_TRANSCRIPT' AND origin = :origin
            AND producer IS :producerId AND start_ms = :start AND end_ms = :end
        """,
    )
    suspend fun deleteCandidateTranscripts(sleepId: Long, start: Duration, end: Duration, origin: AnnotationOrigin, producerId: String?)

    /** Every annotation of a sleep. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId")
    suspend fun annotationsOf(sleepId: Long): List<AnnotationEntity>

    /** Moves the annotation [id] onto the stretch from [start] until [end]. */
    @Query("UPDATE annotation SET start_ms = :start, end_ms = :end WHERE id = :id")
    suspend fun moveAnnotation(id: Long, start: Duration, end: Duration)

    /** The second way's events of a sleep as stored, in order. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'CANDIDATE' ORDER BY start_ms, id")
    suspend fun candidateAnnotations(sleepId: Long): List<AnnotationEntity>

    /** The windows the second way's search during the night heard in a sleep ([AnnotationType.LISTENED]), in order. */
    @Query("SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'LISTENED' ORDER BY start_ms, id")
    suspend fun listenedWindows(sleepId: Long): List<AnnotationEntity>


    /** Removes the marks of the second way's search of a sleep. */
    @Query("DELETE FROM annotation WHERE sleep_id = :sleepId AND type = 'SEARCH' AND producer = :producerId")
    suspend fun deleteSearchMarks(sleepId: Long, producerId: String)

    /** The events of all sleeps, like [events] gives those of one, by sleep and in order. */
    @Query(
        """
        SELECT event.sleep_id, event.id, event.start_ms, event.end_ms, kind.id AS kind_id, kind.name AS kind_name,
            kind.color AS kind_color, NULL AS transcript, NULL AS transcript_origin,
            NULL AS transcript_producer, $IS_HIDDEN AS hidden, $TAG_IDS AS tag_ids, $EVENT_NAME AS name
        FROM annotation AS event
        JOIN event_kind AS kind ON kind.id = event.kind_id
        WHERE event.type = 'EVENT'
        ORDER BY event.sleep_id, event.start_ms, event.id
        """,
    )
    suspend fun allEvents(): List<SleepEventOf>

    /** The annotations of [type] of all sleeps, by sleep and in order. */
    @Query("SELECT * FROM annotation WHERE type = :type ORDER BY sleep_id, start_ms, id")
    suspend fun allAnnotations(type: AnnotationType): List<AnnotationEntity>

    /** The recordings of all sleeps, by sleep and in the order they were recorded. */
    @Query("SELECT * FROM recording ORDER BY sleep_id, started_at, id")
    suspend fun allRecordings(): List<RecordingEntity>

    /** Every event kind, once. */
    @Query("SELECT * FROM event_kind")
    suspend fun allKinds(): List<EventKindEntity>

    /** Every event kind. */
    @Query("SELECT * FROM event_kind")
    fun kinds(): Flow<List<EventKindEntity>>

    /** The user's transcripts that overlap a stretch: their corrections and downgrades of the events in it. */
    @Query(
        """
        SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'TRANSCRIPT' AND origin = 'USER'
            AND start_ms < :end AND end_ms > :start
        ORDER BY id
        """,
    )
    suspend fun userTranscripts(sleepId: Long, start: Duration, end: Duration): List<AnnotationEntity>

    /** Every transcript that overlaps a stretch, the user's and those of analyses. */
    @Query(
        """
        SELECT * FROM annotation WHERE sleep_id = :sleepId AND type = 'TRANSCRIPT'
            AND start_ms < :end AND end_ms > :start
        ORDER BY id
        """,
    )
    suspend fun transcripts(sleepId: Long, start: Duration, end: Duration): List<AnnotationEntity>

    /**
     * The events of all sleeps that have a tag of the user or a name, like
     * [events] gives those of one: the newest sleep first, its events in order, hidden ones too.
     */
    @Query(
        """
        SELECT event.sleep_id, event.id, event.start_ms, event.end_ms, kind.id AS kind_id, kind.name AS kind_name,
            kind.color AS kind_color, NULL AS transcript, NULL AS transcript_origin,
            NULL AS transcript_producer, $IS_HIDDEN AS hidden, $TAG_IDS AS tag_ids, $EVENT_NAME AS name
        FROM annotation AS event
        JOIN event_kind AS kind ON kind.id = event.kind_id
        JOIN sleep ON sleep.id = event.sleep_id
        WHERE event.type = 'EVENT' AND $IS_TAGGED
        ORDER BY sleep.started_at DESC, event.start_ms, event.id
        """,
    )
    fun taggedEvents(): Flow<List<SleepEventOf>>

    /** The transcripts over the tagged events of all sleeps, so [taggedEvents] can show their words. */
    @Query(
        """
        SELECT * FROM annotation AS transcript WHERE type = 'TRANSCRIPT' AND EXISTS (
            SELECT 1 FROM annotation AS mark WHERE mark.sleep_id = transcript.sleep_id AND mark.type IN ('TAG', 'NAME')
                AND mark.start_ms < transcript.end_ms AND mark.end_ms > transcript.start_ms)
        ORDER BY id
        """,
    )
    fun taggedTranscripts(): Flow<List<AnnotationEntity>>

    /** Removes the marks of the tag [tagId] that overlap a stretch, which takes the tag from the events in it. */
    @Query(
        """
        DELETE FROM annotation WHERE sleep_id = :sleepId AND type = 'TAG' AND tag_id = :tagId
            AND start_ms < :end AND end_ms > :start
        """,
    )
    suspend fun deleteTagMarks(sleepId: Long, tagId: Long, start: Duration, end: Duration)

    /** Removes the name marks that overlap a stretch, which takes the name from the events in it. */
    @Query("DELETE FROM annotation WHERE sleep_id = :sleepId AND type = 'NAME' AND start_ms < :end AND end_ms > :start")
    suspend fun deleteNames(sleepId: Long, start: Duration, end: Duration)

    /** Favourite and the user's own tags, Favourite first, the others in the order they were made. */
    @Query("SELECT * FROM tag ORDER BY type = 'OWN', id")
    fun tags(): Flow<List<TagEntity>>

    /** Stores a new tag and returns its id; fails if its name is taken, whatever its case. */
    @Insert
    suspend fun insertTag(tag: TagEntity): Long

    /** Deletes an own tag; its marks go with it. Favourite is never deleted. */
    @Query("DELETE FROM tag WHERE id = :id AND type = 'OWN'")
    suspend fun deleteTag(id: Long)

    /** How many events of all sleeps have the tag [tagId]. */
    @Query(
        """
        SELECT COUNT(*) FROM annotation AS event WHERE event.type = 'EVENT' AND EXISTS (
            SELECT 1 FROM annotation AS mark WHERE mark.sleep_id = event.sleep_id AND mark.type = 'TAG'
                AND mark.tag_id = :tagId AND mark.start_ms < event.end_ms AND mark.end_ms > event.start_ms)
        """,
    )
    suspend fun taggedEventCount(tagId: Long): Int

    /** Removes the hidden marks that overlap a stretch, which shows the events in it again. */
    @Query("DELETE FROM annotation WHERE sleep_id = :sleepId AND type = 'HIDDEN' AND start_ms < :end AND end_ms > :start")
    suspend fun deleteHidden(sleepId: Long, start: Duration, end: Duration)

    /** The kinds the sleep screen offers a filter for. */
    @Query("SELECT * FROM event_kind WHERE has_filter ORDER BY id")
    fun filterKinds(): Flow<List<EventKindEntity>>

    /** The stretches of a sleep's events, in order; with [onlyPending] those without a transcript of the phone. */
    @Query(
        """
        SELECT start_ms AS start, end_ms AS `end` FROM annotation AS event WHERE sleep_id = :sleepId AND type = 'EVENT'
            AND (NOT :onlyPending OR NOT $HAS_DEVICE_TRANSCRIPT)
        ORDER BY start_ms, id
        """,
    )
    suspend fun eventStretches(sleepId: Long, onlyPending: Boolean): List<Stretch>

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
    suspend fun sleepsToTranscribe(producer: String, version: String?): List<Long>

    /** How many of a sleep's events there are and how many have a transcript of the phone. */
    @Query(
        """
        SELECT COUNT(*) AS events, COALESCE(SUM($HAS_DEVICE_TRANSCRIPT), 0) AS transcribed
        FROM annotation AS event WHERE sleep_id = :sleepId AND type = 'EVENT'
        """,
    )
    fun transcriptionCount(sleepId: Long): Flow<TranscriptionCount>

    /** Whether a sleep has an event over exactly the stretch from [start] until [end]. */
    @Query("SELECT EXISTS (SELECT 1 FROM annotation WHERE sleep_id = :sleepId AND type = 'EVENT' AND start_ms = :start AND end_ms = :end)")
    suspend fun hasEvent(sleepId: Long, start: Duration, end: Duration): Boolean

    /**
     * Removes the transcripts of [origin] by the producer [producerId], in any version, over one
     * stretch, before its new one is stored; other models' transcripts of it stay.
     */
    @Query(
        """
        DELETE FROM annotation WHERE sleep_id = :sleepId AND type = 'TRANSCRIPT' AND origin = :origin
            AND producer IS :producerId AND start_ms = :start AND end_ms = :end
        """,
    )
    suspend fun deleteTranscripts(sleepId: Long, start: Duration, end: Duration, origin: AnnotationOrigin, producerId: String?)

    /** Removes one transcript of an analysis, never one of the user. */
    @Query("DELETE FROM annotation WHERE sleep_id = :sleepId AND id = :id AND type = 'TRANSCRIPT' AND origin != 'USER'")
    suspend fun deleteAnalysisTranscript(sleepId: Long, id: Long)

    /** Removes the phone's transcripts of stretches that are no longer an event, after the events were found anew. */
    @Query(
        """
        DELETE FROM annotation WHERE sleep_id = :sleepId AND type = 'TRANSCRIPT' AND origin = 'DEVICE'
            AND NOT EXISTS (SELECT 1 FROM annotation AS event WHERE event.sleep_id = annotation.sleep_id
                AND event.type = 'EVENT' AND event.start_ms = annotation.start_ms AND event.end_ms = annotation.end_ms)
        """,
    )
    suspend fun deleteOrphanedTranscripts(sleepId: Long)

    /** Whether a recording is being written. */
    @Query("SELECT EXISTS (SELECT 1 FROM recording WHERE state = 'RECORDING')")
    suspend fun isRecording(): Boolean
}

/** Whether the event `event` has a transcript of the phone over its stretch. */
private const val HAS_DEVICE_TRANSCRIPT = """
    EXISTS (SELECT 1 FROM annotation AS transcript WHERE transcript.sleep_id = event.sleep_id
        AND transcript.type = 'TRANSCRIPT' AND transcript.origin = 'DEVICE'
        AND transcript.start_ms = event.start_ms AND transcript.end_ms = event.end_ms)
"""

/**
 * Whether the event `event` is hidden: a hidden mark overlaps it. Overlap rather than the same
 * stretch, so the mark keeps to its event when a later search finds it with other bounds.
 */
private const val IS_HIDDEN = """
    EXISTS (SELECT 1 FROM annotation AS mark WHERE mark.sleep_id = event.sleep_id AND mark.type = 'HIDDEN'
        AND mark.start_ms < event.end_ms AND mark.end_ms > event.start_ms)
"""

/**
 * The ids of the user's tags the event `event` has, as a comma-separated list: those of the tag
 * marks that overlap it, like a hidden mark; null without one.
 */
private const val TAG_IDS = """
    (SELECT GROUP_CONCAT(DISTINCT mark.tag_id) FROM annotation AS mark WHERE mark.sleep_id = event.sleep_id
        AND mark.type = 'TAG' AND mark.start_ms < event.end_ms AND mark.end_ms > event.start_ms)
"""

/** The name of the event `event`: that of its latest name mark; null without one. */
private const val EVENT_NAME = """
    (SELECT mark.text FROM annotation AS mark WHERE mark.sleep_id = event.sleep_id AND mark.type = 'NAME'
        AND mark.start_ms < event.end_ms AND mark.end_ms > event.start_ms ORDER BY mark.id DESC LIMIT 1)
"""

/** Whether the event `event` has a tag of the user or a name: a tag or name mark overlaps it. */
private const val IS_TAGGED = """
    EXISTS (SELECT 1 FROM annotation AS mark WHERE mark.sleep_id = event.sleep_id AND mark.type IN ('TAG', 'NAME')
        AND mark.start_ms < event.end_ms AND mark.end_ms > event.start_ms)
"""

/** An event of the sleep [sleepId]. */
data class SleepEventOf(@ColumnInfo(name = "sleep_id") val sleepId: Long, @Embedded val event: SleepEvent)

/** An event of the second way as stored: the event, its human [score] and its [sorting] text ([Sorting.decode]). */
data class CandidateRow(@Embedded val event: SleepEvent, val score: Double?, val sorting: String?) {
    /** The event with how it was sorted. */
    fun sorted(): SleepEvent = event.copy(sorting = Sorting.decode(sorting, score))
}

/**
 * A sleep with its recordings and the events that are not hidden. Only the events of a finished
 * analysis count as found: the analysis writes its events and its sensitivity in one transaction.
 */
private const val SUMMARY = """
    SELECT sleep.id, sleep.started_at, sleep.ended_at, sleep.kind, sleep.date,
        (SELECT COUNT(*) FROM recording WHERE recording.sleep_id = sleep.id) AS recording_count,
        (SELECT COUNT(*) FROM annotation AS event WHERE event.sleep_id = sleep.id AND event.type = 'EVENT'
            AND NOT $IS_HIDDEN) AS event_count,
        (SELECT COALESCE(SUM(event.end_ms - event.start_ms), 0) FROM annotation AS event
            WHERE event.sleep_id = sleep.id AND event.type = 'EVENT' AND NOT $IS_HIDDEN) AS event_ms,
        EXISTS (SELECT 1 FROM annotation WHERE annotation.sleep_id = sleep.id AND type = 'SENSITIVITY'
            AND producer = :producer AND producer_version = :version) AS events_found,
        (SELECT COUNT(*) FROM annotation AS event WHERE event.sleep_id = sleep.id AND event.type = 'EVENT'
            AND $IS_TAGGED) AS tagged_count
    FROM sleep
"""
