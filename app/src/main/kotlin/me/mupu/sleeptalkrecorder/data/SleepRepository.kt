package me.mupu.sleeptalkrecorder.data

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.withTransaction
import me.mupu.sleeptalkrecorder.data.Producers.DETECTOR
import me.mupu.sleeptalkrecorder.detection.SensitivityOverride
import me.mupu.sleeptalkrecorder.detection.SleepTrack
import me.mupu.sleeptalkrecorder.detection.replacedBy
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Sleeps, their recordings and the events found in them, as the screens read and change them.
 *
 * The database holds the timeline of each recording (start instant, sample rate, sample count)
 * that maps positions in its files ([RecordingFiles]) to clock times, and the annotations found in
 * each sleep. The recording session writes through [SleepRecordings], speech recognition through
 * [SleepTranscripts].
 *
 * A sleep owns its rows and its recordings' directories, and [deleteSleep] is the one place that
 * removes them: files a recording owns belong in its directory, and tables with data of a sleep or
 * a recording reference it with `ON DELETE CASCADE`.
 */
class SleepRepository(
    private val database: SleepDatabase,
    private val files: RecordingFiles,
) {

    private val dao = database.sleepDao()

    /** Serialises reading feature tracks, so a track being rebuilt is never rebuilt twice at once. */
    private val trackLock = Mutex()

    /** Serialises finding events, so the last search stored is the one that read the latest settings. */
    private val detectionLock = Mutex()

    /** All sleeps, the most recently started first; the running one, if any, is among them. */
    fun sleeps(): Flow<List<SleepSummary>> = dao.sleeps(DETECTOR.id, DETECTOR.version)

    /** One sleep, or null once it no longer exists; published only when it changed. */
    fun sleep(id: Long): Flow<SleepSummary?> = dao.sleep(id, DETECTOR.id, DETECTOR.version).distinctUntilChanged()

    /**
     * The events of a sleep in order, each with the kind and words of its winning transcript
     * ([withTranscripts]). Until they are found after the stop ([SleepSummary.eventsFound]),
     * these are none or those of an older detector.
     *
     * Every stored annotation reads them anew, also one that changes none of them, as speech
     * recognition stores a few a second; so they are put together off the main thread, the
     * latest reading replaces any not yet taken, and only a changed list is published.
     */
    fun events(sleepId: Long): Flow<List<SleepEvent>> = combine(
        dao.events(sleepId),
        dao.annotations(sleepId, AnnotationType.TRANSCRIPT),
        dao.kinds(),
    ) { events, transcripts, kinds -> withTranscripts(events, transcripts, kinds.associateBy { it.id }) }
        .conflate()
        .flowOn(Dispatchers.Default)
        .distinctUntilChanged()

    /** How many of a sleep's events each model gave a transcript of the phone, by the model's id. */
    fun transcriptModels(sleepId: Long): Flow<Map<String, Int>> =
        dao.transcriptModels(sleepId).map { models -> models.associate { it.producer to it.events } }

    /**
     * Every finished sleep whose events are found, the most recently started first, with its
     * recordings and all its events, hidden ones included. Read anew whenever a sleep, a recording
     * or an annotation changes: the recordings, events and transcripts of all sleeps in one query
     * each, however many sleeps there are.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun storedSleeps(): Flow<List<StoredSleep>> = sleeps().mapLatest { summaries ->
        database.withTransaction {
            storedSleeps(summaries, dao.allRecordings(), dao.allEvents(), dao.allAnnotations(AnnotationType.TRANSCRIPT), dao.allKinds())
        }
    }

    /** How far speech recognition got through a sleep's events; published only when it changed. */
    fun transcriptionCount(sleepId: Long): Flow<TranscriptionCount> = dao.transcriptionCount(sleepId).distinctUntilChanged()

    /** The event kinds the sleep screen offers a filter for. */
    fun filterKinds(): Flow<List<EventKindEntity>> = dao.filterKinds()

    /** The stretches of a sleep's audio in which the microphone was unavailable, in order. */
    fun microphoneUnavailable(sleepId: Long): Flow<List<Stretch>> =
        dao.annotations(sleepId, AnnotationType.MICROPHONE_UNAVAILABLE).map { stretches ->
            stretches.map { it.stretch }
        }

    /**
     * The audio of a sleep as it is stored now: its recordings in order, each with the chunk files
     * that hold its saved samples. Empty if the sleep has no playable audio.
     */
    suspend fun timeline(sleepId: Long): SleepTimeline = files.timelineOf(dao.recordingsOf(sleepId))

    /**
     * The feature track of a sleep, hop by hop over its audio; tracks that are missing or damaged
     * are first extracted again from the audio, which can take a while.
     */
    suspend fun track(sleepId: Long): SleepTrack {
        val recordings = dao.recordingsOf(sleepId)
        val timeline = files.timelineOf(recordings)
        return trackLock.withLock {
            withContext(Dispatchers.IO) {
                SleepTrack.of(
                    recordings.mapIndexed { index, recording ->
                        loadFeatureTrack(files.directoryOf(recording.id), timeline.recordings[index]) to timeline.recordingPositions[index]
                    },
                )
            }
        }
    }

    /** The sensitivities the user set for stretches of a sleep, oldest first. */
    fun sensitivityOverrides(sleepId: Long): Flow<List<SensitivityOverride>> =
        dao.userSensitivityFlow(sleepId).map { annotations -> annotations.map(::overrideOf) }

    /**
     * Sets the sensitivity of a sleep from [start] until [end] of its audio to a start threshold
     * of [thresholdDb] above the baseline, or, with null, takes back what the user set there, and
     * finds the sleep's events again. The user's earlier settings that lie within the stretch are
     * replaced; the new one counts over the others where they overlap.
     *
     * Returns the events now stored, or null if the sleep was not searched.
     */
    suspend fun setSensitivity(sleepId: Long, start: Duration, end: Duration, thresholdDb: Float?): List<Stretch>? {
        database.withTransaction {
            val replaced = dao.userSensitivities(sleepId).map(::overrideOf).replacedBy(start, end)
            dao.deleteAnnotations(replaced.map { it.id })
            if (thresholdDb != null) {
                dao.insertAnnotations(
                    listOf(
                        AnnotationEntity(
                            sleepId = sleepId,
                            type = AnnotationType.SENSITIVITY,
                            start = start,
                            end = end,
                            value = thresholdDb.toDouble(),
                            origin = AnnotationOrigin.USER,
                        ),
                    ),
                )
            }
        }
        return findEvents(sleepId)
    }

    private fun overrideOf(annotation: AnnotationEntity) = SensitivityOverride(
        start = annotation.start,
        end = annotation.end,
        thresholdDb = annotation.value?.toFloat() ?: 0f,
        id = annotation.id,
    )

    /**
     * Finds the events of a sleep, with the sensitivities the user set for it, and stores them,
     * with the automatic sensitivity chosen for it, in place of what the detector found before.
     * Annotations of other origins, such as the user's, stay. Only a finished sleep is searched.
     *
     * Returns the events stored, or null if the sleep was not searched or changed meanwhile.
     */
    suspend fun findEvents(sleepId: Long): List<Stretch>? = detectionLock.withLock {
        // One search at a time, so a search that read the user's settings before they changed
        // never stores its events after the search that follows the change.
        val started = System.nanoTime()
        val end = dao.findSleep(sleepId)?.endedAt ?: return null
        val track = track(sleepId)
        val overrides = dao.userSensitivities(sleepId).map(::overrideOf)
        val loaded = System.nanoTime()
        val detection = withContext(Dispatchers.Default) { track.detect(overrides) }

        val events = detection.events.map { event ->
            result(sleepId, AnnotationType.EVENT, track.positionOf(event.start), track.positionOf(event.end)).copy(kindId = EventKinds.EVENT)
        }
        val sensitivity = result(sleepId, AnnotationType.SENSITIVITY, Duration.ZERO, track.positionOf(track.track.size))
            .copy(value = detection.thresholdDb.toDouble())

        val stored = database.withTransaction {
            // A new recording that continued the sleep meanwhile made these events incomplete;
            // they are found again once it ends.
            if (dao.findSleep(sleepId)?.endedAt != end) return@withTransaction false
            dao.deleteResults(sleepId, AnnotationOrigin.DEVICE, DETECTOR.id)
            dao.insertAnnotations(events + sensitivity)
            // Transcripts follow their events by stretch: events found again unchanged keep theirs.
            dao.deleteOrphanedTranscripts(sleepId)
            true
        }
        if (!stored) return null
        Log.i(
            TAG,
            "Sleep $sleepId: ${events.size} events in ${track.track.size} hops with ${overrides.size} sensitivities " +
                "of the user; reading the track took ${(loaded - started) / 1_000_000} ms, finding the events " +
                "${(System.nanoTime() - loaded) / 1_000_000} ms",
        )
        events.map { it.stretch }
    }

    /**
     * Finds the events of every finished sleep that has none from the current detector: right after
     * a stop, after a recording a killed process left behind, and after the detector changed.
     */
    suspend fun findMissingEvents() {
        for (sleepId in dao.sleepsWithoutEvents(DETECTOR.id, DETECTOR.version)) {
            try {
                findEvents(sleepId)
            } catch (error: SQLiteException) {
                // The sleep stays without events and is tried again with the next stop or start.
                Log.w(TAG, "Could not store the events of sleep $sleepId", error)
            }
        }
    }

    /**
     * Hides [event] with a mark of the user over its stretch, or, with [hidden] false, shows it
     * again by removing every mark that overlaps it. Marks are the user's, so neither a new search
     * for events nor speech recognition removes them.
     */
    suspend fun setHidden(sleepId: Long, event: SleepEvent, hidden: Boolean) {
        database.withTransaction {
            dao.deleteHidden(sleepId, event.start, event.end)
            if (hidden) {
                dao.insertAnnotations(
                    listOf(
                        AnnotationEntity(
                            sleepId = sleepId,
                            type = AnnotationType.HIDDEN,
                            start = event.start,
                            end = event.end,
                            origin = AnnotationOrigin.USER,
                        ),
                    ),
                )
            }
        }
    }

    /**
     * Sets what [event] is, as the user's transcript over its stretch in place of the user's
     * earlier ones that overlap it: a speech event with the [text] they wrote, or with null a
     * murmur; or, with [kindId] the plain event, not speech at all. User transcripts win over
     * every analysis and neither speech recognition nor a new search for events removes them.
     *
     * Returns the user's transcripts it replaced, which [restoreUserTranscripts] puts back.
     */
    suspend fun setUserTranscript(sleepId: Long, event: SleepEvent, kindId: String, text: String?): List<AnnotationEntity> =
        database.withTransaction {
            val replaced = dao.userTranscripts(sleepId, event.start, event.end)
            dao.deleteAnnotations(replaced.map { it.id })
            dao.insertAnnotations(
                listOf(
                    AnnotationEntity(
                        sleepId = sleepId,
                        type = AnnotationType.TRANSCRIPT,
                        start = event.start,
                        end = event.end,
                        kindId = kindId,
                        text = text,
                        origin = AnnotationOrigin.USER,
                    ),
                ),
            )
            replaced
        }

    /**
     * Makes [event] a speech event by hand. The user's earlier transcripts over it go, so if speech
     * recognition found speech there its words show again; otherwise a transcript of the user's
     * own makes it a speech event without words, a murmur whose words they can write later.
     *
     * Returns the user's transcripts it replaced, which [restoreUserTranscripts] puts back.
     */
    suspend fun markSpeech(sleepId: Long, event: SleepEvent): List<AnnotationEntity> =
        database.withTransaction {
            val replaced = dao.userTranscripts(sleepId, event.start, event.end)
            dao.deleteAnnotations(replaced.map { it.id })
            val kinds = dao.allKinds().associateBy { it.id }
            if (needsUserSpeechMark(event, dao.transcripts(sleepId, event.start, event.end), kinds)) {
                dao.insertAnnotations(
                    listOf(
                        AnnotationEntity(
                            sleepId = sleepId,
                            type = AnnotationType.TRANSCRIPT,
                            start = event.start,
                            end = event.end,
                            kindId = EventKinds.SPEECH,
                            text = null,
                            origin = AnnotationOrigin.USER,
                        ),
                    ),
                )
            }
            replaced
        }

    /** Undoes [setUserTranscript] or [markSpeech] on [event]: its user transcripts become the [previous] ones again. */
    suspend fun restoreUserTranscripts(sleepId: Long, event: SleepEvent, previous: List<AnnotationEntity>) {
        database.withTransaction {
            dao.deleteAnnotations(dao.userTranscripts(sleepId, event.start, event.end).map { it.id })
            dao.insertAnnotations(previous.map { it.copy(id = 0) })
        }
    }

    private fun result(sleepId: Long, type: AnnotationType, start: Duration, end: Duration) = AnnotationEntity(
        sleepId = sleepId,
        type = type,
        start = start,
        end = end,
        origin = AnnotationOrigin.DEVICE,
        producer = DETECTOR,
    )

    /** The bytes a sleep's files take up on the device. */
    suspend fun sizeOf(sleepId: Long): Long {
        val recordings = dao.recordingsOf(sleepId)
        return withContext(Dispatchers.IO) {
            recordings.sumOf { recording ->
                files.directoryOf(recording.id).walkBottomUp().filter { it.isFile }.sumOf { it.length() }
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

        val directories = dao.recordingsOf(sleepId).map { files.directoryOf(it.id) }
        val filesGone = withContext(Dispatchers.IO) {
            // Every directory is tried, even after one failed.
            directories.map { it.deleteRecursively() }.all { it }
        }
        if (filesGone) dao.deleteSleep(sleepId)
        filesGone
    }

    private companion object {
        const val TAG = "SleepRepository"
    }

}
