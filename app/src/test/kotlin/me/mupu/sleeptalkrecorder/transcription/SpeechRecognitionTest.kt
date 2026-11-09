package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.data.AnnotationOrigin
import me.mupu.sleeptalkrecorder.data.AnnotationType
import me.mupu.sleeptalkrecorder.data.EventKinds
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
        startMillis = 61_000,
        endMillis = 63_500,
        kindId = kind,
        text = text,
        origin = AnnotationOrigin.DEVICE,
        producer = "parakeet-ultra",
        producerVersion = SpeechRecognition(TranscriptionModel.PARAKEET_ULTRA, FakeTranscriber()).version,
    )

    @Test
    fun `words make the event a speech event with its transcript`() = runTest {
        assertEquals(transcript(EventKinds.SPEECH, "Where are the keys?"), analyse(FakeTranscriber("Where are the keys?")))
    }

    @Test
    fun `fillers make it a speech event without words`() = runTest {
        assertEquals(transcript(EventKinds.SPEECH, null), analyse(FakeTranscriber("Mm-hmm.")))
    }

    @Test
    fun `invented text leaves it a plain event`() = runTest {
        assertEquals(transcript(EventKinds.EVENT, null), analyse(FakeTranscriber("Thank you for watching.")))
    }

    @Test
    fun `a clip too short for the model is not sent to it and stays a plain event`() = runTest {
        val transcriber = FakeTranscriber()

        assertEquals(transcript(EventKinds.EVENT, null), analyse(transcriber, seconds = 0.2))
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
