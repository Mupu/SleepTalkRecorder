package me.mupu.sleeptalkrecorder.data

import android.util.Log
import androidx.room.withTransaction
import me.mupu.sleeptalkrecorder.data.Producers.DETECTOR
import me.mupu.sleeptalkrecorder.playback.SleepAudio
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.transcription.EventClip
import me.mupu.sleeptalkrecorder.transcription.SpeechRecognition
import me.mupu.sleeptalkrecorder.transcription.TranscriptStore
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/** Where speech recognition reads the events of sleeps and the audio around them, and keeps its results. */
class SleepTranscripts(
    private val database: SleepDatabase,
    private val files: RecordingFiles,
) : TranscriptStore {

    private val dao = database.sleepDao()

    /** The timeline clips were last read from, with the recordings it was read for. */
    @Volatile
    private var clipTimeline: Pair<List<RecordingEntity>, SleepTimeline>? = null

    override suspend fun sleepsToTranscribe(): List<Long> = dao.sleepsToTranscribe(DETECTOR.id, DETECTOR.version)

    override suspend fun eventsOf(sleepId: Long, onlyPending: Boolean): List<Stretch> =
        dao.eventStretches(sleepId, onlyPending)

    /**
     * The audio around [event], [SpeechRecognition.PADDING] before and after it, cut back at the
     * start of a recording and where the microphone was unavailable, like the lead-in and tail.
     */
    override suspend fun clip(sleepId: Long, event: Stretch): EventClip {
        val recordings = dao.recordingsOf(sleepId)
        val timeline = clipTimeline?.takeIf { it.first == recordings }?.second
            ?: files.timelineOf(recordings).also { clipTimeline = recordings to it }
        val unavailable = dao.annotations(sleepId, AnnotationType.MICROPHONE_UNAVAILABLE).first().map { it.stretch }
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
            if (!dao.hasEvent(sleepId, event.start, event.end)) return@withTransaction
            // Each model keeps its own result, so the results of models can be compared.
            for ((origin, producer) in annotations.map { it.origin to it.producer?.id }.distinct()) {
                dao.deleteTranscripts(sleepId, event.start, event.end, origin, producer)
            }
            dao.insertAnnotations(annotations)
        }
    }

    override suspend fun isRecording(): Boolean = dao.isRecording()

    private companion object {
        const val TAG = "SleepTranscripts"
    }
}
