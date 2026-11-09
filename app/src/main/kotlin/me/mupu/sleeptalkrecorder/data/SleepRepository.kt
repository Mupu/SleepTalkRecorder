package me.mupu.sleeptalkrecorder.data

import android.database.sqlite.SQLiteException
import android.util.Log
import androidx.room.withTransaction
import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.detection.FeatureTrackFile
import me.mupu.sleeptalkrecorder.detection.SensitivityOverride
import me.mupu.sleeptalkrecorder.detection.SleepTrack
import me.mupu.sleeptalkrecorder.detection.replacedBy
import me.mupu.sleeptalkrecorder.playback.SleepAudio
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.recording.ChunkedWavWriter
import me.mupu.sleeptalkrecorder.recording.NewRecording
import me.mupu.sleeptalkrecorder.recording.RecordingStore
import me.mupu.sleeptalkrecorder.time.RecordingClock
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.transcription.EventClip
import me.mupu.sleeptalkrecorder.transcription.SpeechRecognition
import me.mupu.sleeptalkrecorder.transcription.TranscriptStore
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
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
) : RecordingStore, TranscriptStore {

    private val dao = database.sleepDao()

    /** The timeline clips were last read from, with the recordings it was read for. */
    @Volatile
    private var clipTimeline: Pair<List<RecordingEntity>, SleepTimeline>? = null

    /** Serialises reading feature tracks, so a track being rebuilt is never rebuilt twice at once. */
    private val trackLock = Mutex()

    /** Serialises finding events, so the last search stored is the one that read the latest settings. */
    private val detectionLock = Mutex()

    /** All sleeps, the most recently started first; the running one, if any, is among them. */
    fun sleeps(): Flow<List<SleepSummary>> = dao.sleeps(DETECTOR, DETECTOR_VERSION)

    /** One sleep, or null once it no longer exists. */
    fun sleep(id: Long): Flow<SleepSummary?> = dao.sleep(id, DETECTOR, DETECTOR_VERSION)

    /**
     * The events of a sleep in order, each with the kind and words of its winning transcript
     * ([withTranscripts]). Until they are found after the stop ([SleepSummary.eventsFound]),
     * these are none or those of an older detector.
     */
    fun events(sleepId: Long): Flow<List<SleepEvent>> = combine(
        dao.events(sleepId),
        dao.annotations(sleepId, AnnotationType.TRANSCRIPT),
        dao.kinds(),
    ) { events, transcripts, kinds -> withTranscripts(events, transcripts, kinds.associateBy { it.id }) }

    /** How many of a sleep's events each model gave a transcript of the phone, by the model's id. */
    fun transcriptModels(sleepId: Long): Flow<Map<String, Int>> =
        dao.transcriptModels(sleepId).map { models -> models.associate { it.producer to it.events } }

    /**
     * Every finished sleep whose events are found, the most recently started first, with its
     * recordings and all its events, hidden ones included. Read anew whenever a sleep, a recording
     * or an annotation changes.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun storedSleeps(): Flow<List<StoredSleep>> = sleeps().mapLatest { summaries ->
        summaries.filter { !it.isRunning && it.eventsFound }.map { summary ->
            StoredSleep(summary, dao.recordingsOf(summary.id), events(summary.id).first())
        }
    }

    /** How far speech recognition got through a sleep's events. */
    fun transcriptionCount(sleepId: Long): Flow<TranscriptionCount> = dao.transcriptionCount(sleepId)

    /** The event kinds the sleep screen offers a filter for. */
    fun filterKinds(): Flow<List<EventKindEntity>> = dao.filterKinds()

    /** The stretches of a sleep's audio in which the microphone was unavailable, in order. */
    fun microphoneUnavailable(sleepId: Long): Flow<List<Stretch>> =
        dao.annotations(sleepId, AnnotationType.MICROPHONE_UNAVAILABLE).map { stretches ->
            stretches.map { Stretch(Duration.ofMillis(it.startMillis), Duration.ofMillis(it.endMillis)) }
        }

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
                            startMillis = start.toMillis(),
                            endMillis = end.toMillis(),
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
        start = Duration.ofMillis(annotation.startMillis),
        end = Duration.ofMillis(annotation.endMillis),
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

        fun millis(hop: Int) = track.positionOf(hop).toMillis()
        val events = detection.events.map { event ->
            result(sleepId, AnnotationType.EVENT, millis(event.start), millis(event.end)).copy(kindId = EventKinds.EVENT)
        }
        val sensitivity = result(sleepId, AnnotationType.SENSITIVITY, 0, millis(track.track.size))
            .copy(value = detection.thresholdDb.toDouble())

        val stored = database.withTransaction {
            // A new recording that continued the sleep meanwhile made these events incomplete;
            // they are found again once it ends.
            if (dao.findSleep(sleepId)?.endedAt != end) return@withTransaction false
            dao.deleteResults(sleepId, AnnotationOrigin.DEVICE, DETECTOR)
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
        events.map { Stretch(Duration.ofMillis(it.startMillis), Duration.ofMillis(it.endMillis)) }
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

    /**
     * Hides [event] with a mark of the user over its stretch, or, with [hidden] false, shows it
     * again by removing every mark that overlaps it. Marks are the user's, so neither a new search
     * for events nor speech recognition removes them.
     */
    suspend fun setHidden(sleepId: Long, event: SleepEvent, hidden: Boolean) {
        database.withTransaction {
            dao.deleteHidden(sleepId, event.startMillis, event.endMillis)
            if (hidden) {
                dao.insertAnnotations(
                    listOf(
                        AnnotationEntity(
                            sleepId = sleepId,
                            type = AnnotationType.HIDDEN,
                            startMillis = event.startMillis,
                            endMillis = event.endMillis,
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
            val replaced = dao.userTranscripts(sleepId, event.startMillis, event.endMillis)
            dao.deleteAnnotations(replaced.map { it.id })
            dao.insertAnnotations(
                listOf(
                    AnnotationEntity(
                        sleepId = sleepId,
                        type = AnnotationType.TRANSCRIPT,
                        startMillis = event.startMillis,
                        endMillis = event.endMillis,
                        kindId = kindId,
                        text = text,
                        origin = AnnotationOrigin.USER,
                    ),
                ),
            )
            replaced
        }

    /** Undoes [setUserTranscript] on [event]: its user transcripts become the [previous] ones again. */
    suspend fun restoreUserTranscripts(sleepId: Long, event: SleepEvent, previous: List<AnnotationEntity>) {
        database.withTransaction {
            dao.deleteAnnotations(dao.userTranscripts(sleepId, event.startMillis, event.endMillis).map { it.id })
            dao.insertAnnotations(previous.map { it.copy(id = 0) })
        }
    }

    override suspend fun sleepsToTranscribe(): List<Long> = dao.sleepsToTranscribe(DETECTOR, DETECTOR_VERSION)

    override suspend fun eventsOf(sleepId: Long, onlyPending: Boolean): List<Stretch> =
        dao.eventStretches(sleepId, onlyPending).map { Stretch(Duration.ofMillis(it.startMillis), Duration.ofMillis(it.endMillis)) }

    /**
     * The audio around [event], [SpeechRecognition.PADDING] before and after it, cut back at the
     * start of a recording and where the microphone was unavailable, like the lead-in and tail.
     */
    override suspend fun clip(sleepId: Long, event: Stretch): EventClip {
        val recordings = dao.recordingsOf(sleepId)
        val timeline = clipTimeline?.takeIf { it.first == recordings }?.second
            ?: timelineOf(recordings).also { clipTimeline = recordings to it }
        val unavailable = microphoneUnavailable(sleepId).first()
        val padding = SpeechRecognition.PADDING
        val stretch = timeline.uninterrupted((event.start - padding).coerceAtLeast(Duration.ZERO), event.end + padding, event, unavailable)
        val samples = withContext(Dispatchers.IO) {
            try {
                SleepAudio(timeline).use { it.clip(stretch) }
            } catch (error: IOException) {
                // Without its audio the event cannot be recognised and stays a plain event.
                Log.w(TAG, "Cannot read the audio of an event of sleep $sleepId at ${event.start}", error)
                FloatArray(0)
            }
        }
        return EventClip(sleepId, event, samples, SleepAudio.sampleRateOf(timeline))
    }

    override suspend fun store(sleepId: Long, event: Stretch, annotations: List<AnnotationEntity>) {
        database.withTransaction {
            // Events found anew meanwhile no longer hold this stretch; the result has no event left.
            if (!dao.hasEvent(sleepId, event.start.toMillis(), event.end.toMillis())) return@withTransaction
            for (origin in annotations.map { it.origin }.distinct()) {
                dao.deleteTranscripts(sleepId, event.start.toMillis(), event.end.toMillis(), origin)
            }
            dao.insertAnnotations(annotations)
        }
    }

    override suspend fun isRecording(): Boolean = dao.isRecording()

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

    override suspend fun startRecording(startedAt: Instant, sampleRate: Int): NewRecording =
        database.withTransaction {
            val last = dao.latestSleep()
            val lastEnd = last?.endedAt
            val sleepId = if (last != null && lastEnd != null &&
                SleepDate.continues(last.startedAt, lastEnd, startedAt, ZoneId.systemDefault())
            ) {
                // The events are found again over the longer audio once this recording ends; the
                // positions of everything else stay, because the new audio follows the old.
                dao.reopenSleep(last.id)
                dao.deleteResults(last.id, AnnotationOrigin.DEVICE, DETECTOR)
                last.id
            } else {
                dao.insertSleep(SleepEntity(startedAt = startedAt, endedAt = null))
            }
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

    override suspend fun markMicrophoneUnavailable(recordingId: Long, fromFrame: Long, untilFrame: Long) {
        database.withTransaction {
            val recording = dao.recording(recordingId) ?: return@withTransaction
            val clock = RecordingClock(recording.startedAt, recording.sampleRate)
            // Where the recording starts in the sleep's audio: after every recording before it.
            val recordingStart = dao.recordingsOf(recording.sleepId)
                .takeWhile { it.id != recordingId }
                .fold(Duration.ZERO) { start, earlier ->
                    start + RecordingClock(earlier.startedAt, earlier.sampleRate).durationOf(earlier.frames)
                }
            val startMillis = (recordingStart + clock.durationOf(fromFrame)).toMillis()

            dao.deleteResultAt(recording.sleepId, AnnotationType.MICROPHONE_UNAVAILABLE, startMillis, AnnotationOrigin.DEVICE, RECORDER)
            dao.insertAnnotations(
                listOf(
                    AnnotationEntity(
                        sleepId = recording.sleepId,
                        type = AnnotationType.MICROPHONE_UNAVAILABLE,
                        startMillis = startMillis,
                        endMillis = (recordingStart + clock.durationOf(untilFrame)).toMillis(),
                        origin = AnnotationOrigin.DEVICE,
                        producer = RECORDER,
                    ),
                ),
            )
        }
    }

    /**
     * Repairs and finishes the recordings a process left unfinished because it was killed, crashed
     * or lost its power, so that no sleep stays "running" without a recorder. Their chunk files get
     * headers that declare all the audio they hold, which keeps the seconds after the last flush
     * that reached the storage; if the files cannot be repaired, the samples of the last flush
     * count. Call it once at process start, before a new recording can begin.
     */
    suspend fun finishInterruptedRecordings() {
        for (recording in dao.unfinishedRecordings()) {
            val frames = try {
                withContext(Dispatchers.IO) { ChunkedWavWriter.repair(directoryOf(recording.id), recording.sampleRate) }
            } catch (error: IOException) {
                Log.w(TAG, "Could not repair recording ${recording.id}", error)
                recording.frames
            }
            finish(recording.id, frames)
        }
    }

    private fun directoryOf(recordingId: Long) = File(recordingsDirectory, recordingId.toString())

    private companion object {
        const val TAG = "SleepRepository"

        /** The producer of the annotations the recording itself makes. */
        const val RECORDER = "recorder"

        /** The producer of the detector's annotations. */
        const val DETECTOR = "loudness"

        /** Changes with the detector's rules and with the feature format, so either change finds events again. */
        const val DETECTOR_VERSION = "${EventDetector.VERSION}/${FeatureTrackFile.VERSION}"
    }
}
