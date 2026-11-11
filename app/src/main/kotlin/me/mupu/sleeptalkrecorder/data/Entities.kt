package me.mupu.sleeptalkrecorder.data

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Ignore
import androidx.room.Index
import androidx.room.PrimaryKey
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.time.SleepDate
import java.time.Duration
import java.time.Instant

/**
 * A sleep: everything recorded between going to bed and getting up. All instants are UTC and are
 * shown in the device's zone.
 */
@Entity(tableName = "sleep")
data class SleepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The instant of the first sample of the sleep's first recording. */
    @ColumnInfo(name = "started_at") val startedAt: Instant,
    /** The instant after the last sample of its last recording; null while it is recorded. */
    @ColumnInfo(name = "ended_at") val endedAt: Instant?,
    /**
     * Whether it is a night or a nap and the day it is named after (columns `kind` and `date`),
     * given by the sleep windows when it started and kept when the windows change.
     */
    @Embedded val date: SleepDate,
)

/** Whether a recording is still being written. */
enum class RecordingState { RECORDING, FINISHED }

/**
 * One uninterrupted recording of a sleep, stored as chunk WAV files in its own directory. Its
 * timeline is the sample clock: a position is a sample count, and its clock time is [startedAt]
 * plus that count divided by [sampleRate].
 */
@Entity(
    tableName = "recording",
    foreignKeys = [
        ForeignKey(
            entity = SleepEntity::class,
            parentColumns = ["id"],
            childColumns = ["sleep_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sleep_id")],
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sleep_id") val sleepId: Long,
    /** The instant of the first sample, taken from the audio clock. */
    @ColumnInfo(name = "started_at") val startedAt: Instant,
    /** Samples per second. */
    @ColumnInfo(name = "sample_rate") val sampleRate: Int,
    /** Samples safely written so far; the total once [state] is finished. */
    val frames: Long,
    val state: RecordingState,
)

/**
 * A kind of event, such as a plain loudness event or a speech event. Kinds are rows, not a
 * list in code: what an event's kind is comes from its annotations, and how it looks and filters
 * comes from here, so new kinds need no schema or UI change.
 */
@Entity(tableName = "event_kind")
data class EventKindEntity(
    /** A stable key that annotations refer to, such as `event`. */
    @PrimaryKey val id: String,
    /** The user's name for the kind; null for the kinds the app ships, which it names in the app language. */
    val name: String?,
    /** The colour events of this kind are drawn in. */
    val color: KindColor,
    /** Whether the sleep screen offers a filter that shows only events of this kind. */
    @ColumnInfo(name = "has_filter") val hasFilter: Boolean,
)

/** What an [AnnotationEntity] says about its stretch of a sleep. */
enum class AnnotationType {
    /** The stretch is an event of the annotation's kind. */
    EVENT,

    /** The start threshold above the baseline, in dB ([AnnotationEntity.value]), used in the stretch. */
    SENSITIVITY,

    /**
     * The microphone was unavailable: a call, another app or the privacy toggle held it, and the
     * stretch holds the silence Android delivered instead of the room.
     */
    MICROPHONE_UNAVAILABLE,

    /**
     * What speech recognition made of the event over the same stretch: the kind it gives the event
     * ([AnnotationEntity.kindId], the speech kind or the plain event) and the words it recognised
     * ([AnnotationEntity.text], null for a murmur or a plain event). The user's transcript is their
     * correction of the words or their verdict that the event is not speech; it wins over those of
     * analyses and, like a hidden mark, counts for every event that overlaps it.
     */
    TRANSCRIPT,

    /**
     * The user hid the events over the stretch: they stay in the sleep but are left out of the
     * list and of cut playback unless hidden events are shown, and drawn grey like silence. Any
     * event that overlaps the stretch counts as hidden, so the mark keeps to its event when a
     * later search finds it with slightly different bounds.
     */
    HIDDEN,

    /**
     * The user keeps the events over the stretch as favourites, with the name they gave them
     * ([AnnotationEntity.text], null without one). Like a hidden mark, it counts for every event
     * that overlaps it; an event is never a favourite and hidden at once.
     */
    FAVOURITE,
}

/** Where an annotation comes from. */
enum class AnnotationOrigin {
    /** An analysis that ran on the phone. */
    DEVICE,

    /** An analysis on a server the user chose. */
    SERVER,

    /** The user; their annotations win over every analysis. */
    USER,
}

/**
 * Who stated an annotation: the analysis or model with the id [id], such as `loudness`, in its
 * [version], which changes whenever the same input could give other annotations; null for a
 * producer whose results never change, such as the recorder.
 */
data class Producer(
    @ColumnInfo(name = "producer") val id: String,
    @ColumnInfo(name = "producer_version") val version: String? = null,
)

/**
 * One result about a stretch of a sleep, with its origin. Everything found in or said about a
 * sleep is an annotation: the events the detector found and the sensitivity it chose, the
 * transcripts of speech recognition, the events the user hid and the user's corrections.
 * Each layer is added beside the others, so an analysis that runs again replaces only its own
 * annotations and never the user's.
 *
 * The stretch is given as positions in the sleep's audio (the axis the player plays, on which the
 * gaps between recordings take no time), so it does not depend on the detector's hop size.
 */
@Entity(
    tableName = "annotation",
    foreignKeys = [
        ForeignKey(
            entity = SleepEntity::class,
            parentColumns = ["id"],
            childColumns = ["sleep_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = EventKindEntity::class, parentColumns = ["id"], childColumns = ["kind_id"]),
    ],
    // Annotations are looked up by sleep, type and stretch: an event's transcripts and hidden
    // marks, which the sleep screen, the library and speech recognition ask for every event.
    indices = [Index("sleep_id", "type", "start_ms", "end_ms"), Index("kind_id")],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sleep_id") val sleepId: Long,
    val type: AnnotationType,
    /** The start of the stretch: its position in the sleep's audio, stored in milliseconds. */
    @ColumnInfo(name = "start_ms") val start: Duration,
    /** The end of the stretch (exclusive) in the sleep's audio, stored in milliseconds. */
    @ColumnInfo(name = "end_ms") val end: Duration,
    /** The event kind an [AnnotationType.EVENT] annotation gives its stretch. */
    @ColumnInfo(name = "kind_id") val kindId: String? = null,
    /** A number whose meaning depends on [type]. */
    val value: Double? = null,
    /** A text whose meaning depends on [type]. */
    val text: String? = null,
    val origin: AnnotationOrigin,
    /** The analysis or model that made it; null for the user. */
    @Embedded val producer: Producer? = null,
) {
    /** The stretch of the sleep's audio it is about. */
    val stretch: Stretch get() = Stretch(start, end)
}

/** How far speech recognition got through a sleep's events. */
data class TranscriptionCount(
    /** The sleep's events. */
    val events: Int,
    /** Those the phone's speech recognition has a result for. */
    val transcribed: Int,
) {
    /** The events still waiting for speech recognition. */
    val pending: Int get() = events - transcribed
}

/** A sleep as the library lists it. */
data class SleepSummary(
    val id: Long,
    @ColumnInfo(name = "started_at") val startedAt: Instant,
    @ColumnInfo(name = "ended_at") val endedAt: Instant?,
    /** Its name, given when it started. */
    @Embedded val date: SleepDate,
    @ColumnInfo(name = "recording_count") val recordingCount: Int,
    /** How many events were found in it, without those the user hid. */
    @ColumnInfo(name = "event_count") val eventCount: Int,
    /** How long those events last together, in milliseconds. */
    @ColumnInfo(name = "event_ms") val eventMillis: Long,
    /** Whether its events have been found, which happens right after the stop. */
    @ColumnInfo(name = "events_found") val eventsFound: Boolean,
    /** How many of its events the user keeps as favourites; automatic deletion keeps such a sleep. */
    @ColumnInfo(name = "favourite_count") val favouriteCount: Int = 0,
) {
    /** Whether the sleep is still being recorded. */
    val isRunning: Boolean get() = endedAt == null
}

/**
 * An event of a sleep with its kind, as the sleep screen shows it. The kind and the transcript are
 * those of the event's winning transcript annotation, if it has one, else the detector's kind.
 */
data class SleepEvent(
    val id: Long,
    /** Where the event starts in the sleep's audio, stored in milliseconds. */
    @ColumnInfo(name = "start_ms") val start: Duration,
    /** Where it ends in the sleep's audio, stored in milliseconds. */
    @ColumnInfo(name = "end_ms") val end: Duration,
    /** The kind's id; see [EventKindEntity] for [kindName] and [kindColor]. */
    @ColumnInfo(name = "kind_id") val kindId: String,
    @ColumnInfo(name = "kind_name") val kindName: String?,
    @ColumnInfo(name = "kind_color") val kindColor: KindColor,
    /** The words recognised in the event; null if none were or it was not transcribed yet. */
    val transcript: String? = null,
    /** Who wrote [transcript]: the user or an analysis; null without a transcript annotation. */
    @ColumnInfo(name = "transcript_origin") val transcriptOrigin: AnnotationOrigin? = null,
    /** The model that wrote it, such as `parakeet-ultra`; null for the user. */
    @ColumnInfo(name = "transcript_producer") val transcriptProducer: String? = null,
    /** Whether the user hid the event: a [AnnotationType.HIDDEN] annotation overlaps its stretch. */
    val hidden: Boolean = false,
    /** Whether the user keeps it as a favourite: a [AnnotationType.FAVOURITE] annotation overlaps it. */
    val favourite: Boolean = false,
    /** The name the user gave the favourite; null without one or when it is no favourite. */
    val name: String? = null,
    /**
     * What each analysis made of the event, one result per model, the one that wins first; also
     * when the user's own transcript wins over all of them.
     */
    @Ignore val results: List<TranscriptResult> = emptyList(),
) {
    /** The event as the database reads it, before [withTranscripts] adds the results of the analyses. */
    constructor(
        id: Long,
        start: Duration,
        end: Duration,
        kindId: String,
        kindName: String?,
        kindColor: KindColor,
        transcript: String?,
        transcriptOrigin: AnnotationOrigin?,
        transcriptProducer: String?,
        hidden: Boolean,
        favourite: Boolean,
        name: String?,
    ) : this(id, start, end, kindId, kindName, kindColor, transcript, transcriptOrigin, transcriptProducer, hidden, favourite, name, emptyList())

    /** Whether the user corrected the transcript or said the event is not speech. */
    val corrected: Boolean get() = transcriptOrigin == AnnotationOrigin.USER

    /**
     * The results the open transcript shows beside each other, each with its model: those of
     * every analysis when more than one recognised the event, none once the user wrote their own
     * text or said what the event is, because only that is shown then.
     */
    val comparedResults: List<TranscriptResult> get() = if (corrected || results.size < 2) emptyList() else results

    /** Whether this is a speech event: one with words, or a murmur without them. */
    val isSpeech: Boolean get() = kindId == EventKinds.SPEECH

    /** The stretch of the sleep's audio the event takes. */
    val stretch: Stretch get() = Stretch(start, end)
}

/** What one analysis, such as a speech recognition model, made of an event: its transcript annotation. */
data class TranscriptResult(
    /** The id of the transcript annotation, which deleting the result removes. */
    val annotationId: Long,
    val origin: AnnotationOrigin,
    /** The id of the model that made it, such as `parakeet-ultra`. */
    val producer: String?,
    /** The kind it gives the event: the speech kind, or the plain event kind when it found no speech. */
    val kindId: String,
    /** The words recognised; null for a murmur or no speech. */
    val text: String?,
) {
    /** Whether it makes the event a speech event, with words or as a murmur. */
    val isSpeech: Boolean get() = kindId == EventKinds.SPEECH
}

/** A finished sleep with its recordings in order and all its events, hidden ones included. */
data class StoredSleep(val summary: SleepSummary, val recordings: List<RecordingEntity>, val events: List<SleepEvent>)
