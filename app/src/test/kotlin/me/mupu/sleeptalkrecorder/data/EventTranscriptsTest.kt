package me.mupu.sleeptalkrecorder.data

import org.junit.Assert.assertEquals
import org.junit.Test

class EventTranscriptsTest {

    private val kinds = EventKinds.builtIn.associateBy { it.id }

    /** An event as the detector stores it, a plain event until a transcript says otherwise. */
    private fun event(id: Long, startMillis: Long, endMillis: Long) =
        SleepEvent(id, startMillis, endMillis, EventKinds.EVENT, null, "primary")

    private var nextId = 1L

    private fun transcript(
        startMillis: Long,
        endMillis: Long,
        kind: String,
        text: String?,
        origin: AnnotationOrigin = AnnotationOrigin.DEVICE,
        producer: String? = "parakeet-ultra",
    ) = AnnotationEntity(
        id = nextId++,
        sleepId = 1,
        type = AnnotationType.TRANSCRIPT,
        startMillis = startMillis,
        endMillis = endMillis,
        kindId = kind,
        text = text,
        origin = origin,
        producer = producer.takeIf { origin != AnnotationOrigin.USER },
    )

    private fun correction(startMillis: Long, endMillis: Long, text: String) =
        transcript(startMillis, endMillis, EventKinds.SPEECH, text, AnnotationOrigin.USER)

    private fun notSpeech(startMillis: Long, endMillis: Long) =
        transcript(startMillis, endMillis, EventKinds.EVENT, null, AnnotationOrigin.USER)

    private data class Shown(val kind: String, val text: String?, val origin: AnnotationOrigin?, val producer: String?)

    private fun shown(events: List<SleepEvent>, transcripts: List<AnnotationEntity>) =
        withTranscripts(events, transcripts, kinds).map { Shown(it.kindId, it.transcript, it.transcriptOrigin, it.transcriptProducer) }

    @Test
    fun `an event shows the kind and words of the phone's transcript over its stretch`() {
        val shown = shown(
            listOf(event(1, 1_000, 2_000), event(2, 5_000, 6_000), event(3, 9_000, 9_500)),
            listOf(
                transcript(1_000, 2_000, EventKinds.SPEECH, "hello"),
                transcript(5_000, 6_000, EventKinds.EVENT, null),
            ),
        )

        assertEquals(
            listOf(
                Shown(EventKinds.SPEECH, "hello", AnnotationOrigin.DEVICE, "parakeet-ultra"),
                Shown(EventKinds.EVENT, null, AnnotationOrigin.DEVICE, "parakeet-ultra"),
                Shown(EventKinds.EVENT, null, null, null),
            ),
            shown,
        )
    }

    @Test
    fun `the phone's transcript of another stretch is not shown on an event`() {
        val shown = shown(listOf(event(1, 1_000, 2_200)), listOf(transcript(1_000, 2_000, EventKinds.SPEECH, "hello")))

        assertEquals(listOf(Shown(EventKinds.EVENT, null, null, null)), shown)
    }

    @Test
    fun `the user's correction and downgrade win over the phone's transcript`() {
        val shown = shown(
            listOf(event(1, 1_000, 2_000), event(2, 5_000, 6_000)),
            listOf(
                transcript(1_000, 2_000, EventKinds.SPEECH, "hallo"),
                correction(1_000, 2_000, "hello"),
                transcript(5_000, 6_000, EventKinds.SPEECH, "uh"),
                notSpeech(5_000, 6_000),
            ),
        )

        assertEquals(
            listOf(
                Shown(EventKinds.SPEECH, "hello", AnnotationOrigin.USER, null),
                Shown(EventKinds.EVENT, null, AnnotationOrigin.USER, null),
            ),
            shown,
        )
    }

    @Test
    fun `corrections and downgrades survive a re-run with another model on events found with other bounds`() {
        val corrected = correction(1_000, 2_000, "hello")
        val downgraded = notSpeech(5_000, 6_000)

        // The events were found again with a changed sensitivity, and a re-run with Whisper replaced
        // every transcript of the phone; the user's annotations stayed as they were.
        val shown = shown(
            listOf(event(4, 900, 2_300), event(5, 5_200, 6_100), event(6, 8_000, 9_000)),
            listOf(
                corrected,
                downgraded,
                transcript(900, 2_300, EventKinds.SPEECH, "hollow", producer = "whisper-small"),
                transcript(5_200, 6_100, EventKinds.SPEECH, "uh huh", producer = "whisper-small"),
                transcript(8_000, 9_000, EventKinds.SPEECH, "good night", producer = "whisper-small"),
            ),
        )

        assertEquals(
            listOf(
                Shown(EventKinds.SPEECH, "hello", AnnotationOrigin.USER, null),
                Shown(EventKinds.EVENT, null, AnnotationOrigin.USER, null),
                Shown(EventKinds.SPEECH, "good night", AnnotationOrigin.DEVICE, "whisper-small"),
            ),
            shown,
        )
    }

    @Test
    fun `of several corrections over an event the latest counts`() {
        val shown = shown(
            listOf(event(1, 1_000, 4_000)),
            listOf(correction(1_000, 2_000, "first"), correction(3_000, 4_000, "second")),
        )

        assertEquals(listOf(Shown(EventKinds.SPEECH, "second", AnnotationOrigin.USER, null)), shown)
    }

    @Test
    fun `a correction keeps to its event and not to its neighbours`() {
        val shown = shown(
            listOf(event(1, 1_000, 2_000), event(2, 2_000, 3_000)),
            listOf(correction(1_000, 2_000, "hello")),
        )

        assertEquals(EventKinds.EVENT, shown[1].kind)
    }
}
