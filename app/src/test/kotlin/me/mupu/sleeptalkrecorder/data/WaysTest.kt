package me.mupu.sleeptalkrecorder.data

import me.mupu.sleeptalkrecorder.detection.FoundBy
import me.mupu.sleeptalkrecorder.detection.SearchSettings
import me.mupu.sleeptalkrecorder.detection.Verdict
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

/** Both ways of finding events side by side: the comparison and the units speech recognition hears. */
class WaysTest {

    private fun event(id: Long, from: Long, until: Long, kind: String = EventKinds.NOISE, sorting: Sorting? = null, tags: Set<Long> = emptySet()) =
        SleepEvent(id, Duration.ofSeconds(from), Duration.ofSeconds(until), kind, null, KindColor.Theme(ThemeRole.PRIMARY), tagIds = tags)
            .copy(sorting = sorting)

    private fun sorted(verdict: Verdict, score: Float) = Sorting(verdict, FoundBy.LOUDNESS, score, 12f)

    @Test
    fun `the comparison merges overlapping events and says which ways found them`() {
        val old = listOf(event(1, 10, 12, EventKinds.SPEECH, tags = setOf(Tags.FAVOURITE)), event(2, 30, 31))
        val new = listOf(
            event(10, 11, 14, sorting = sorted(Verdict.BACKGROUND, 0.02f)),
            event(11, 13, 15, sorting = sorted(Verdict.UNSURE, 0.2f)),
            event(12, 50, 52, sorting = sorted(Verdict.BACKGROUND, 0.01f)),
        )

        val compared = compareWays(old, new)

        assertEquals(listOf(WaysFound.BOTH, WaysFound.ONLY_OLD, WaysFound.ONLY_NEW), compared.map { it.ways })
        val both = compared[0]
        assertEquals(Stretch(Duration.ofSeconds(10), Duration.ofSeconds(15)), both.stretch)
        assertEquals(1L, both.id)
        assertEquals(EventKinds.SPEECH, both.kindId)
        assertEquals(setOf(Tags.FAVOURITE), both.tagIds)
        // The best verdict of the second way's events wins.
        assertEquals(Verdict.UNSURE, both.sorting?.verdict)
        // Background is left out only where the first way found nothing.
        assertEquals(listOf(false, false, true), compared.map { it.background })
        assertEquals(true, EventTag.System.Ways(WaysFound.ONLY_NEW) in compared[2].tags)
        assertEquals(true, EventTag.System.Background in compared[2].tags)
    }

    private fun transcript(id: Long, from: Long, until: Long, text: String?, type: AnnotationType = AnnotationType.CANDIDATE_TRANSCRIPT) =
        AnnotationEntity(
            id = id, sleepId = 1, type = type, start = Duration.ofSeconds(from), end = Duration.ofSeconds(until),
            kindId = if (text == null) EventKinds.NOISE else EventKinds.SPEECH, text = text, origin = AnnotationOrigin.DEVICE,
            producer = Producer("parakeet-ultra", "3"),
        )

    private val kinds = EventKinds.builtIn.associateBy { it.id }

    @Test
    fun `a long event of the new way is heard in pieces of at most a minute`() {
        val pieces = piecesOf(Stretch(Duration.ofSeconds(10), Duration.ofSeconds(160)))

        assertEquals(3, pieces.size)
        assertEquals(Stretch(Duration.ofSeconds(10), Duration.ofSeconds(60)), pieces.first())
        assertEquals(listOf(Stretch(Duration.ofSeconds(1), Duration.ofSeconds(2))), piecesOf(Stretch(Duration.ofSeconds(1), Duration.ofSeconds(2))))
    }

    @Test
    fun `a new way's event shows an old event's transcript it lies within, else its own pieces joined`() {
        val old = withTranscripts(listOf(event(1, 10, 20)), listOf(transcript(5, 10, 20, "hello", AnnotationType.TRANSCRIPT)), kinds, "parakeet-ultra")
        val inside = event(10, 12, 15)
        val long = event(11, 100, 220)
        val pieces = piecesOf(long.stretch)
        val own = listOf(transcript(20, 0, 0, "good").copy(start = pieces[0].start, end = pieces[0].end), transcript(21, 0, 0, "night").copy(start = pieces[1].start, end = pieces[1].end))

        val shown = withCandidateTranscripts(listOf(inside, long), old, own, emptyList(), kinds, "parakeet-ultra")

        assertEquals("hello", shown[0].transcript)
        assertEquals(EventKinds.SPEECH, shown[1].kindId)
        assertEquals("good night", shown[1].transcript)
        assertEquals(true, shown[1].results.single().newWay)
    }

    @Test
    fun `the new way's results stack after an old event's own, which still wins`() {
        val old = withTranscripts(listOf(event(1, 10, 20)), listOf(transcript(5, 10, 20, "hello", AnnotationType.TRANSCRIPT)), kinds, "parakeet-ultra")
        val candidate = withCandidateTranscripts(listOf(event(10, 8, 21)), old, listOf(transcript(20, 8, 21, "hello there")), emptyList(), kinds, "parakeet-ultra")

        val stacked = withNewWayResults(old, candidate).single()

        assertEquals("hello", stacked.transcript)
        assertEquals(listOf("hello", "hello there"), stacked.results.map { it.text })
        assertEquals(listOf(false, true), stacked.results.map { it.newWay })
        assertEquals(old, withNewWayResults(old, emptyList()))
    }

    @Test
    fun `an event over several old events keeps its edits to the old way`() {
        val old = listOf(event(1, 10, 12), event(2, 13, 14))

        val shown = withSharedMarks(listOf(event(10, 9, 15), event(11, 13, 14)), old)

        assertEquals(listOf(true, false), shown.map { it.sharesMarks })
    }

    @Test
    fun `a search mark is read back as it was stored`() {
        val mark = SearchMark(SearchSettings(), Duration.ofMinutes(30), done = true, partial = true)
        val stored = AnnotationEntity(sleepId = 1, type = AnnotationType.SEARCH, start = Duration.ZERO, end = mark.searchedTo, value = 1.0, text = mark.text(), origin = AnnotationOrigin.DEVICE)
        val night = SearchMark(SearchSettings(listening = false), Duration.ofMinutes(75), done = false, provisional = true)

        assertEquals(mark, SearchMark.of(stored))
        assertEquals(false, SearchMark.of(stored.copy(value = 0.0, text = SearchSettings().encode()))?.done)
        assertEquals(night, SearchMark.of(stored.copy(end = night.searchedTo, value = 0.0, text = night.text())))
    }

    @Test
    fun `the windows heard during the night are read back as they were stored`() {
        val windows = ListenedWindows(3598, 4505, listOf(3605, 3606, 3607, 3610, 4500), 0.18f)
        val stored = AnnotationEntity(
            sleepId = 1, type = AnnotationType.LISTENED, start = Duration.ofSeconds(3598), end = Duration.ofSeconds(4505),
            value = 0.18f.toDouble(), text = windows.text(), origin = AnnotationOrigin.DEVICE,
        )

        assertEquals("3605-3607 3610 4500", windows.text())
        assertEquals(windows, ListenedWindows.of(stored))
    }

    private fun searched(id: Long, type: AnnotationType, from: Long, until: Long, origin: AnnotationOrigin = AnnotationOrigin.DEVICE) = AnnotationEntity(
        id = id, sleepId = 1, type = type, start = Duration.ofSeconds(from), end = Duration.ofSeconds(until), origin = origin,
        producer = Producers.SORTING.takeIf { origin == AnnotationOrigin.DEVICE && type != AnnotationType.CANDIDATE_TRANSCRIPT },
    )

    @Test
    fun `the search after the night replaces the night's events part by part and keeps marks and transcripts that still fit`() {
        val night = listOf(
            searched(1, AnnotationType.SEARCH, 0, 3600),
            searched(2, AnnotationType.LISTENED, 0, 905),
            searched(3, AnnotationType.CANDIDATE, 100, 102),
            searched(4, AnnotationType.CANDIDATE, 500, 503),
            searched(5, AnnotationType.CANDIDATE, 2000, 2001),
            // The user's tag over the first event and the transcripts of the first three events.
            searched(6, AnnotationType.TAG, 100, 102, AnnotationOrigin.USER),
            transcript(7, 100, 102, "hello"),
            transcript(8, 500, 503, null),
            transcript(9, 2000, 2001, "night"),
        )
        val first = Duration.ofMinutes(15)

        // The first part of the search after the night replaces the night's events up to 15 minutes only.
        val part = searchPartChanges(night, Duration.ZERO, SearchMark(SearchSettings(), first, done = false), emptyList())
        assertEquals(SearchPartChanges(listOf(1L, 3L, 4L)), part)

        // Once it is done, it finds the first event again unchanged, the second one a second longer
        // and the third one a hop later, onto which its transcript moves.
        val moved = Stretch(Duration.ofMillis(2_000_020), Duration.ofSeconds(2001))
        val found = listOf(Stretch(Duration.ofSeconds(100), Duration.ofSeconds(102)), Stretch(Duration.ofSeconds(500), Duration.ofSeconds(504)), moved)
        val done = searchPartChanges(night, Duration.ZERO, SearchMark(SearchSettings(), Duration.ofMinutes(60), done = true), found)
        assertEquals(SearchPartChanges(listOf(1L, 2L, 3L, 4L, 5L, 8L), mapOf(9L to moved)), done)
    }

    @Test
    fun `how an event was sorted is read back as it was stored`() {
        val sorting = Sorting(Verdict.UNSURE, FoundBy.BOTH, 0.25f, 12.5f)

        assertEquals(sorting, Sorting.decode(sorting.encode(), 0.25))
        assertEquals(null, Sorting.decode("UNSURE", 0.25))
    }
}
