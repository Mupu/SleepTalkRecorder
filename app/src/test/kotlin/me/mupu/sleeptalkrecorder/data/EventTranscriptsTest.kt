package me.mupu.sleeptalkrecorder.data

import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.time.SleepKind
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class EventTranscriptsTest {

    private val kinds = EventKinds.builtIn.associateBy { it.id }

    /** An event as the detector stores it, a plain event until a transcript says otherwise. */
    private fun event(id: Long, startMillis: Long, endMillis: Long) =
        SleepEvent(id, Duration.ofMillis(startMillis), Duration.ofMillis(endMillis), EventKinds.EVENT, null, KindColor.Theme(ThemeRole.PRIMARY))

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
        start = Duration.ofMillis(startMillis),
        end = Duration.ofMillis(endMillis),
        kindId = kind,
        text = text,
        origin = origin,
        producer = producer?.takeIf { origin != AnnotationOrigin.USER }?.let { Producer(it, "1") },
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

    @Test
    fun `the readings of all sleeps are put together by sleep, each event with its own sleep's transcript`() {
        fun summary(id: Long, ended: Boolean) = SleepSummary(
            id, Instant.EPOCH, Instant.EPOCH.takeIf { ended }, SleepDate(SleepKind.NIGHT, LocalDate.EPOCH),
            recordingCount = 1, eventCount = 1, eventMillis = 1_000, eventsFound = true,
        )
        fun recording(sleepId: Long) = RecordingEntity(sleepId, sleepId, Instant.EPOCH, 16_000, 16_000, RecordingState.FINISHED)
        // The same stretch is an event in every sleep; only the second sleep has a transcript of it.
        val stored = storedSleeps(
            summaries = listOf(summary(3, ended = false), summary(2, ended = true), summary(1, ended = true)),
            recordings = listOf(recording(1), recording(2), recording(3)),
            events = (1L..3L).map { SleepEventOf(it, event(it, 1_000, 2_000)) },
            transcripts = listOf(transcript(1_000, 2_000, EventKinds.SPEECH, "hello").copy(sleepId = 2)),
            kinds = kinds.values.toList(),
        )

        assertEquals(listOf(2L, 1L), stored.map { it.summary.id })
        assertEquals(listOf(listOf(2L), listOf(1L)), stored.map { sleep -> sleep.recordings.map { it.sleepId } })
        assertEquals(listOf("hello", null), stored.map { it.events.single().transcript })
    }
}
