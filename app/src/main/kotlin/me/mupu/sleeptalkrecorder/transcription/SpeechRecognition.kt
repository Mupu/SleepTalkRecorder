package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.data.AnnotationOrigin
import me.mupu.sleeptalkrecorder.data.AnnotationType
import me.mupu.sleeptalkrecorder.data.EventKinds
import me.mupu.sleeptalkrecorder.data.Producer
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import java.time.Duration
import kotlin.math.ceil

/** A speech recognition model, loaded and ready: mono samples in, its text out. */
interface Transcriber : AutoCloseable {
    /** The text the model recognises in [samples] (from -1 to 1); empty if none. Blocks while it runs. */
    fun transcribe(samples: FloatArray, sampleRate: Int): String
}

/**
 * The phone's speech recognition as an [EventAnalysis]: the clip of every event goes to the
 * [transcriber] as it is, without a voice activity gate or noise suppression in front (both lost
 * whispers and gained nothing), and the [TranscriptFilter] decides from the text whether the event
 * is a speech event. Each event gets one [AnnotationType.TRANSCRIPT] annotation, also when it stays a
 * plain event, so it counts as done.
 */
class SpeechRecognition(model: TranscriptionModel, private val transcriber: Transcriber) : EventAnalysis {

    override val origin = AnnotationOrigin.DEVICE
    override val producer = Producer(model.id, "${TranscriptFilter.VERSION}")

    override suspend fun analyse(clip: EventClip): List<AnnotationEntity> {
        val minSamples = clip.sampleRate * MIN_CLIP.toMillis() / 1000
        val verdict = if (clip.samples.size < minSamples) {
            // sherpa-onnx fails on input of no frames, and so little audio holds no word.
            Verdict.NoSpeech
        } else {
            TranscriptFilter.judge(pieces(clip).joinToString(" ") { transcriber.transcribe(it, clip.sampleRate).trim() })
        }

        return listOf(
            AnnotationEntity(
                sleepId = clip.sleepId,
                type = AnnotationType.TRANSCRIPT,
                start = clip.event.start,
                end = clip.event.end,
                kindId = if (verdict == Verdict.NoSpeech) EventKinds.EVENT else EventKinds.SPEECH,
                text = (verdict as? Verdict.Speech)?.text,
                origin = origin,
                producer = producer,
            ),
        )
    }

    override fun close() = transcriber.close()

    /**
     * The clip in pieces of equal length of at most [MAX_PIECE], because the models work on
     * stretches of that order (Whisper on 30 s at most) and their memory grows with the input.
     */
    private fun pieces(clip: EventClip): List<FloatArray> {
        val maxSamples = clip.sampleRate * MAX_PIECE.toMillis() / 1000
        val count = ceil(clip.samples.size / maxSamples.toDouble()).toInt()
        return (0 until count).map { piece ->
            clip.samples.copyOfRange(
                (clip.samples.size.toLong() * piece / count).toInt(),
                (clip.samples.size.toLong() * (piece + 1) / count).toInt(),
            )
        }
    }

    companion object {
        /** How much of the sound before and after an event its clip holds, as the models were measured with. */
        val PADDING: Duration = Duration.ofMillis(500)

        /** Clips shorter than this are not sent to the model. */
        val MIN_CLIP: Duration = Duration.ofMillis(300)

        /** The longest piece of a clip the model gets at once. */
        val MAX_PIECE: Duration = Duration.ofSeconds(30)
    }
}
