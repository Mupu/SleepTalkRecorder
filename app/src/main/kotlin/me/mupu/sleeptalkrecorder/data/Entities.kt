package me.mupu.sleeptalkrecorder.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Duration
import java.time.Instant

/**
 * A sleep: everything recorded between going to bed and getting up. All instants are UTC and are
 * shown in the device's zone; whether a sleep is a night or a nap follows from its start.
 */
@Entity(tableName = "sleep")
data class SleepEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** The instant of the first sample of the sleep's first recording. */
    @ColumnInfo(name = "started_at") val startedAt: Instant,
    /** The instant after the last sample of its last recording; null while it is recorded. */
    @ColumnInfo(name = "ended_at") val endedAt: Instant?,
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
 * A kind of event, such as a plain loudness event or (later) a speech event. Kinds are rows, not a
 * list in code: what an event's kind is comes from its annotations, and how it looks and filters
 * comes from here, so new kinds need no schema or UI change.
 */
@Entity(tableName = "event_kind")
data class EventKindEntity(
    /** A stable key that annotations refer to, such as `event`. */
    @PrimaryKey val id: String,
    /** The user's name for the kind; null for the kinds the app ships, which it names in the app language. */
    val name: String?,
    /**
     * The colour events of this kind are drawn in: a colour role of the app's theme (`primary`,
     * `secondary`, `tertiary`), so it follows light and dark mode, or a fixed `#RRGGBB`.
     */
    val color: String,
    /** Whether the sleep screen offers a filter that shows only events of this kind. */
    @ColumnInfo(name = "has_filter") val hasFilter: Boolean,
)

/** What an [AnnotationEntity] says about its stretch of a sleep. */
enum class AnnotationType {
    /** The stretch is an event of the annotation's kind. */
    EVENT,

    /** The start threshold above the baseline, in dB ([AnnotationEntity.value]), used in the stretch. */
    SENSITIVITY,
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
 * One result about a stretch of a sleep, with its origin. Everything found in or said about a
 * sleep is an annotation: the events the detector found and the sensitivity it chose, and later
 * hidden events, transcripts and the user's corrections. Each layer is added beside the others,
 * so an analysis that runs again replaces only its own annotations and never the user's.
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
    indices = [Index("sleep_id", "type"), Index("kind_id")],
)
data class AnnotationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sleep_id") val sleepId: Long,
    val type: AnnotationType,
    /** The start of the stretch: its position in the sleep's audio, in milliseconds. */
    @ColumnInfo(name = "start_ms") val startMillis: Long,
    /** The end of the stretch (exclusive), in milliseconds of the sleep's audio. */
    @ColumnInfo(name = "end_ms") val endMillis: Long,
    /** The event kind an [AnnotationType.EVENT] annotation gives its stretch. */
    @ColumnInfo(name = "kind_id") val kindId: String? = null,
    /** A number whose meaning depends on [type]. */
    val value: Double? = null,
    val origin: AnnotationOrigin,
    /** The analysis or model that made it, such as `loudness`; null for the user. */
    val producer: String? = null,
    /** The version of [producer] that made it. */
    @ColumnInfo(name = "producer_version") val producerVersion: String? = null,
)

/** A sleep as the library lists it. */
data class SleepSummary(
    val id: Long,
    @ColumnInfo(name = "started_at") val startedAt: Instant,
    @ColumnInfo(name = "ended_at") val endedAt: Instant?,
    @ColumnInfo(name = "recording_count") val recordingCount: Int,
    /** How many events were found in it. */
    @ColumnInfo(name = "event_count") val eventCount: Int,
    /** How long its events last together, in milliseconds. */
    @ColumnInfo(name = "event_ms") val eventMillis: Long,
    /** Whether its events have been found, which happens right after the stop. */
    @ColumnInfo(name = "events_found") val eventsFound: Boolean,
) {
    /** Whether the sleep is still being recorded. */
    val isRunning: Boolean get() = endedAt == null
}

/** An event of a sleep with its kind, as the sleep screen shows it. */
data class SleepEvent(
    val id: Long,
    @ColumnInfo(name = "start_ms") val startMillis: Long,
    @ColumnInfo(name = "end_ms") val endMillis: Long,
    /** The kind's id; see [EventKindEntity] for [kindName] and [kindColor]. */
    @ColumnInfo(name = "kind_id") val kindId: String,
    @ColumnInfo(name = "kind_name") val kindName: String?,
    @ColumnInfo(name = "kind_color") val kindColor: String,
) {
    /** Where the event starts in the sleep's audio. */
    val start: Duration get() = Duration.ofMillis(startMillis)

    /** Where it ends in the sleep's audio. */
    val end: Duration get() = Duration.ofMillis(endMillis)
}
