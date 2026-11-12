package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.data.AnnotationOrigin
import me.mupu.sleeptalkrecorder.data.AnnotationType
import me.mupu.sleeptalkrecorder.data.EventKinds
import me.mupu.sleeptalkrecorder.data.Producer
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechRecognitionTest {

    /** Answers with [outputs] in turn and remembers how many samples it was given each time. */
    private class FakeTranscriber(vararg outputs: String) : Transcriber {
        private val outputs = outputs.toMutableList()
        val calls = mutableListOf<Int>()

        override fun transcribe(samples: FloatArray, sampleRate: Int): String {
            calls += samples.size
            return outputs.removeAt(0)
        }

        override fun close() = Unit
    }

    private val event = Stretch(Duration.ofMillis(61_000), Duration.ofMillis(63_500))

    private fun clip(seconds: Double) = EventClip(sleepId = 7, event = event, samples = FloatArray((seconds * 16_000).toInt()), sampleRate = 16_000)

    private suspend fun analyse(transcriber: Transcriber, seconds: Double = 3.5) =
        SpeechRecognition(TranscriptionModel.PARAKEET_ULTRA, transcriber).analyse(clip(seconds)).single()

    private fun transcript(kind: String, text: String?) = AnnotationEntity(
        sleepId = 7,
        type = AnnotationType.TRANSCRIPT,
        start = Duration.ofMillis(61_000),
        end = Duration.ofMillis(63_500),
        kindId = kind,
        text = text,
        origin = AnnotationOrigin.DEVICE,
        producer = SpeechRecognition(TranscriptionModel.PARAKEET_ULTRA, FakeTranscriber()).producer,
    )

    @Test
    fun `words make the event a speech event with its transcript`() = runTest {
        assertEquals(transcript(EventKinds.SPEECH, "Where are the keys?"), analyse(FakeTranscriber("Where are the keys?")))
    }

    @Test
    fun `fillers make it a murmur`() = runTest {
        assertEquals(transcript(EventKinds.MURMUR, null), analyse(FakeTranscriber("Mm-hmm.")))
    }

    @Test
    fun `invented text leaves it a noise`() = runTest {
        assertEquals(transcript(EventKinds.NOISE, null), analyse(FakeTranscriber("Thank you for watching.")))
    }

    @Test
    fun `a stored transcript is judged again by the current filter`() {
        fun stored(text: String) = transcript(EventKinds.SPEECH, text).copy(producer = Producer(TranscriptionModel.PARAKEET_ULTRA.id, "1"))
        assertEquals(transcript(EventKinds.NOISE, null), SpeechRecognition.judgedAgain(stored("(door")))
        assertEquals(transcript(EventKinds.SPEECH, "I said no"), SpeechRecognition.judgedAgain(stored("I said no")))
    }

    @Test
    fun `a stored murmur of the speech kind becomes a murmur, a stored noise stays one`() {
        fun stored(kind: String) = transcript(kind, null).copy(producer = Producer(TranscriptionModel.PARAKEET_ULTRA.id, "2"))
        assertEquals(transcript(EventKinds.MURMUR, null), SpeechRecognition.judgedAgain(stored(EventKinds.SPEECH)))
        assertEquals(transcript(EventKinds.NOISE, null), SpeechRecognition.judgedAgain(stored(EventKinds.NOISE)))
    }

    @Test
    fun `a clip too short for the model is not sent to it and stays a plain event`() = runTest {
        val transcriber = FakeTranscriber()

        assertEquals(transcript(EventKinds.NOISE, null), analyse(transcriber, seconds = 0.2))
        assertEquals(emptyList<Int>(), transcriber.calls)
    }

    @Test
    fun `a long clip goes to the model in pieces of at most 30 seconds and their words are joined`() = runTest {
        val transcriber = FakeTranscriber("I was", "", "at home")

        assertEquals(transcript(EventKinds.SPEECH, "I was at home"), analyse(transcriber, seconds = 70.0))
        assertEquals(70 * 16_000, transcriber.calls.sum())
        assertEquals(3, transcriber.calls.size)
    }
}
