package me.mupu.sleeptalkrecorder.data

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.withTransaction
import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.detection.FeatureTrackFile
import me.mupu.sleeptalkrecorder.detection.SleepTrack
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sleeps, their recordings, where the audio of each recording lives, and the events found in them.
 *
 * The audio of recording `n` is in [recordingsDirectory]`/n/` as chunk WAV files next to its
 * feature track; the database holds the timeline (start instant, sample rate, sample count) that
 * maps positions in those files to clock times, and the annotations found in each sleep.
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

    /** Serialises reading feature tracks, so a track being rebuilt is never rebuilt twice at once. */
    private val trackLock = Mutex()

    /** All sleeps, the most recently started first; the running one, if any, is among them. */
    fun sleeps(): Flow<List<SleepSummary>> = dao.sleeps(DETECTOR, DETECTOR_VERSION)

    /** One sleep, or null once it no longer exists. */
    fun sleep(id: Long): Flow<SleepSummary?> = dao.sleep(id, DETECTOR, DETECTOR_VERSION)

    /**
     * The events of a sleep in order. Until they are found after the stop
     * ([SleepSummary.eventsFound]), these are none or those of an older detector.
     */
    fun events(sleepId: Long): Flow<List<SleepEvent>> = dao.events(sleepId)

    /**
     * The audio of a sleep as it is stored now: its recordings in order, each with the chunk files
     * that hold its saved samples. Empty if the sleep has no playable audio.
     */
    suspend fun timeline(sleepId: Long): SleepTimeline = timelineOf(dao.recordingsOf(sleepId))

    /**
     * The feature track of a sleep, hop by hop over its audio; tracks that are missing or damaged
     * are first extracted again from the audio, which can take a while.
     */
    suspend fun track(sleepId: Long): SleepTrack {
        val recordings = dao.recordingsOf(sleepId)
        val timeline = timelineOf(recordings)
        return trackLock.withLock {
            withContext(Dispatchers.IO) {
                SleepTrack.of(
                    recordings.mapIndexed { index, recording ->
                        loadFeatureTrack(directoryOf(recording.id), timeline.recordings[index]) to timeline.recordingPositions[index]
                    },
                )
            }
        }
    }

    /**
     * Finds the events of a sleep and stores them, with the sensitivity chosen for it, in place of
     * what the detector found before. Annotations of other origins, such as the user's, stay.
     */
    suspend fun findEvents(sleepId: Long) {
        val started = System.nanoTime()
        val track = track(sleepId)
        val loaded = System.nanoTime()
        val detection = withContext(Dispatchers.Default) { EventDetector.detect(track.track) }

        fun millis(hop: Int) = track.positionOf(hop).toMillis()
        val events = detection.events.map { event ->
            result(sleepId, AnnotationType.EVENT, millis(event.start), millis(event.end)).copy(kindId = EventKinds.EVENT)
        }
        val sensitivity = result(sleepId, AnnotationType.SENSITIVITY, 0, millis(track.track.size))
            .copy(value = detection.thresholdDb.toDouble())

        database.withTransaction {
            dao.deleteResults(sleepId, AnnotationOrigin.DEVICE, DETECTOR)
            dao.insertAnnotations(events + sensitivity)
        }
        Log.i(
            TAG,
            "Sleep $sleepId: ${events.size} events in ${track.track.size} hops; reading the track took " +
                "${(loaded - started) / 1_000_000} ms, finding the events ${(System.nanoTime() - loaded) / 1_000_000} ms",
        )
    }

    /**
     * Finds the events of every finished sleep that has none from the current detector: right after
     * a stop, after a recording a killed process left behind, and after the detector changed.
     */
    suspend fun findMissingEvents() {
        for (sleepId in dao.sleepsWithoutEvents(DETECTOR, DETECTOR_VERSION)) {
            try {
                findEvents(sleepId)
            } catch (error: SQLiteException) {
                // The sleep stays without events and is tried again with the next stop or start.
                Log.w(TAG, "Could not store the events of sleep $sleepId", error)
            }
        }
    }

    private fun result(sleepId: Long, type: AnnotationType, startMillis: Long, endMillis: Long) = AnnotationEntity(
        sleepId = sleepId,
        type = type,
        startMillis = startMillis,
        endMillis = endMillis,
        origin = AnnotationOrigin.DEVICE,
        producer = DETECTOR,
        producerVersion = DETECTOR_VERSION,
    )

    private suspend fun timelineOf(recordings: List<RecordingEntity>): SleepTimeline = withContext(Dispatchers.IO) {
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

    private companion object {
        const val TAG = "SleepRepository"

        /** The producer of the detector's annotations. */
        const val DETECTOR = "loudness"

        /** Changes with the detector's rules and with the feature format, so either change finds events again. */
        const val DETECTOR_VERSION = "${EventDetector.VERSION}/${FeatureTrackFile.VERSION}"
    }
}
