package me.mupu.sleeptalkrecorder.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
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

/** A sleep as the library lists it. */
data class SleepSummary(
    val id: Long,
    @ColumnInfo(name = "started_at") val startedAt: Instant,
    @ColumnInfo(name = "ended_at") val endedAt: Instant?,
    @ColumnInfo(name = "recording_count") val recordingCount: Int,
) {
    /** Whether the sleep is still being recorded. */
    val isRunning: Boolean get() = endedAt == null
}
