package me.mupu.sleeptalkrecorder.detection

import java.time.Duration
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The second way of finding events on fixed scores and synthetic levels (50 hops per second). */
class SoundSearchTest {

    private val search = SoundSearch(SearchSettings())

    @Test
    fun `background needs a low score and a slow start while the onset vote is on`() {
        assertEquals(Verdict.BACKGROUND, search.verdict(score = 0.05f, sudden = false))
        assertEquals(Verdict.UNSURE, search.verdict(score = 0.05f, sudden = true))
        assertEquals(Verdict.UNSURE, search.verdict(score = 0.06f, sudden = false))
        assertEquals(Verdict.HUMAN, search.verdict(score = 0.5f, sudden = false))
        assertEquals(Verdict.BACKGROUND, SoundSearch(SearchSettings(onsetVote = false)).verdict(score = 0.05f, sudden = true))
    }

    @Test
    fun `windows that touch or overlap make one listened stretch`() {
        // Windows 0, 2 and 3 cover seconds 0-2, 2-4 and 3-5; window 7 covers 7-9.
        val scores = floatArrayOf(0.2f, 0.1f, 0.3f, 0.19f, 0f, 0f, 0f, 0.5f)

        assertEquals(listOf(0 to 5, 7 to 9), SoundSearch.listenedSpans(scores, threshold = 0.18f))
    }

    @Test
    fun `a listened stretch the loudness search missed becomes a candidate, one it found is found by both`() {
        // A word at 60 s that jumps up 20 dB; a whisper at 120 s that stays below the threshold.
        val level = FloatArray(HOPS_PER_SECOND * 180) { -60f }
        for (hop in 60 * HOPS_PER_SECOND until 62 * HOPS_PER_SECOND) level[hop] = -40f
        val word = HopRange(60 * HOPS_PER_SECOND, 62 * HOPS_PER_SECOND)
        val detection = Detection(level, FloatArray(level.size) { -60f }, 10f, emptyList(), listOf(word))
        val listened = listOf(HopRange(59 * HOPS_PER_SECOND, 62 * HOPS_PER_SECOND), HopRange(120 * HOPS_PER_SECOND, 122 * HOPS_PER_SECOND))

        val candidates = search.candidates(detection, listened)

        assertEquals(listOf(FoundBy.BOTH, FoundBy.LISTENING), candidates.map { it.foundBy })
        assertEquals(true, candidates[0].sudden)
        assertEquals(20f, candidates[0].riseDb, 0.01f)
        assertEquals(false, candidates[1].sudden)
    }

    @Test
    fun `a long candidate is sorted by its loudest 9 s with half a second around it`() {
        // 30 s at -50 dB with 9 s at -30 dB from second 15 on.
        val codes = ByteArray(HOPS_PER_SECOND * 60) { FeatureTrack.levelCode(-60.0) }
        for (hop in 10 * HOPS_PER_SECOND until 40 * HOPS_PER_SECOND) codes[hop] = FeatureTrack.levelCode(-50.0)
        for (hop in 15 * HOPS_PER_SECOND until 24 * HOPS_PER_SECOND) codes[hop] = FeatureTrack.levelCode(-30.0)
        val track = SleepTrack.of(listOf(trackOf(codes) to Duration.ZERO))
        val candidate = Candidate(HopRange(10 * HOPS_PER_SECOND, 40 * HOPS_PER_SECOND), FoundBy.LOUDNESS, 0f, sudden = false)

        val (start, end) = SoundSearch.clipOf(candidate, track, Duration.ofSeconds(60))

        assertEquals(Duration.ofMillis(14_500), start)
        assertEquals(Duration.ofMillis(24_500), end)
    }

    @Test
    fun `a quiet clip is brought to -25 dBFS and a short one filled up to half a second`() {
        val clip = FloatArray(1_600) { 0.001f * sin(2 * PI * 440 * it / 16_000).toFloat() }

        val views = SoundSearch.viewsOf(clip, 16_000)

        assertEquals(3, views.size)
        assertEquals(8_000, views[0].size)
        val rms = kotlin.math.sqrt(views[0].sumOf { it.toDouble() * it } / views[0].size)
        assertEquals(-25.0, 20 * kotlin.math.log10(rms), 0.1)
    }

    @Test
    fun `the search sorts every candidate by the best view of the sorting model, the same part by part`() = runTest {
        // One minute with a loud word at 20 s; the listener hears a human sound from 39 s on only.
        val codes = ByteArray(HOPS_PER_SECOND * 60) { FeatureTrack.levelCode(-60.0 + (it % 3)) }
        for (hop in 20 * HOPS_PER_SECOND until 21 * HOPS_PER_SECOND) codes[hop] = FeatureTrack.levelCode(-30.0)
        val track = SleepTrack.of(listOf(trackOf(codes) to Duration.ZERO))
        val samples = object : SleepSamples {
            override val sampleRate = 16_000
            override val length = 60L * 16_000
            override fun read(from: Long, count: Int) = FloatArray(count) {
                val at = from + it
                (if (at / 16_000 in 39L..41L) 0.1f else 0.001f) * sin(2 * PI * 1_000 * at / 16_000).toFloat()
            }
        }
        val listener = FakeTagger { samples -> if (samples.maxOf { kotlin.math.abs(it) } > 0.05f) 0.9f else 0f }
        val sorter = FakeTagger { 0.03f }

        val sorted = search.search(track, samples, listener, sorter)

        assertEquals(listOf(FoundBy.LOUDNESS, FoundBy.LISTENING), sorted.map { it.candidate.foundBy })
        // The word starts suddenly, so a low score leaves it unsure; the listened stretch rises slowly.
        assertEquals(listOf(Verdict.UNSURE, Verdict.BACKGROUND), sorted.map { it.verdict })
        assertTrue(sorted.all { it.score == 0.03f })
        // Parts of 40 s, as stored one by one, cut the sound heard from 38 s on and give the same.
        assertEquals(sorted, search.search(track, samples, listener, sorter, partLength = Duration.ofSeconds(40)))
    }

    /** A night of [minutes]: a 1 kHz tone with loud stretches at the [loud] seconds, the rest quiet. */
    private fun tone(minutes: Int, loud: Set<Long>) = object : SleepSamples {
        override val sampleRate = 16_000
        override val length = minutes * 60L * 16_000
        override fun read(from: Long, count: Int) = FloatArray(maxOf(0, minOf(count.toLong(), length - from).toInt())) {
            val at = from + it
            (if (at / 16_000 in loud) 0.1f else 0.001f) * sin(2 * PI * 1_000 * at / 16_000).toFloat()
        }
    }

    private val loudTagger = FakeTagger { samples -> if (samples.maxOf { kotlin.math.abs(it) } > 0.05f) 0.9f else 0f }

    @Test
    fun `a part searched during the night is final once settled and stays as the longer recording finds it`() = runTest {
        // 75 minutes with words at 47:00, 52:30 and 59:50; the listener also hears a sound at 50:10 the loudness search misses.
        val codes = ByteArray(HOPS_PER_SECOND * 75 * 60) { FeatureTrack.levelCode(-60.0 + (it % 3)) }
        for (word in listOf(47 * 60, 52 * 60 + 30, 59 * 60 + 50)) {
            for (hop in word * HOPS_PER_SECOND until (word + 1) * HOPS_PER_SECOND) codes[hop] = FeatureTrack.levelCode(-35.0)
        }
        val loud = setOf(47L * 60, 50L * 60 + 10, 50L * 60 + 11)
        val sorter = FakeTagger { samples -> samples.maxOf { kotlin.math.abs(it) } }
        fun recorded(minutes: Int) = SleepTrack.of(listOf(trackOf(codes.copyOf(HOPS_PER_SECOND * minutes * 60)) to Duration.ZERO))
        val whole = recorded(75)
        val prefix = recorded(66)
        val night = search.loudness(prefix, SlidingThreshold.LOOK_BACK)
        val from = Duration.ofMinutes(45)
        val until = Duration.ofMinutes(60)

        assertTrue(search.isSettled(prefix, night, from, until, Duration.ofMinutes(66)))
        assertFalse(search.isSettled(prefix, night, until, until + SoundSearch.PART_LENGTH, Duration.ofMinutes(66)))
        assertFalse(search.isSettled(recorded(50), search.loudness(recorded(50), SlidingThreshold.LOOK_BACK), Duration.ZERO, SoundSearch.PART_LENGTH, Duration.ofMinutes(50)))

        val provisional = search.searchPart(prefix, night, tone(66, loud), loudTagger, sorter, from, until)!!
        val later = search.searchPart(whole, search.loudness(whole, SlidingThreshold.LOOK_BACK), tone(75, loud), loudTagger, sorter, from, until)

        assertEquals(listOf(FoundBy.BOTH, FoundBy.LISTENING, FoundBy.LOUDNESS, FoundBy.LOUDNESS), provisional.map { it.candidate.foundBy })
        assertEquals(provisional, later)
    }

    @Test
    fun `a search again takes the windows heard and the scores given before instead of running the models`() = runTest {
        val codes = ByteArray(HOPS_PER_SECOND * 60) { FeatureTrack.levelCode(-60.0 + (it % 3)) }
        for (hop in 20 * HOPS_PER_SECOND until 21 * HOPS_PER_SECOND) codes[hop] = FeatureTrack.levelCode(-30.0)
        val track = SleepTrack.of(listOf(trackOf(codes) to Duration.ZERO))
        val samples = tone(1, loud = setOf(39, 40, 41))
        val detection = search.loudness(track)
        val heard = HashMap<Int, Boolean>()
        val first = search.searchPart(track, detection, samples, loudTagger, FakeTagger { 0.03f }, Duration.ZERO, Duration.ofMinutes(1), onHeard = { window, human -> heard[window] = human })!!
        val memory = object : SearchMemory {
            override fun heard(window: Int) = heard[window]
            override fun score(hops: HopRange) = first.find { it.candidate.hops == hops }?.score
        }
        val unused = FakeTagger { error("a model ran again") }

        assertEquals(first, search.searchPart(track, detection, samples, unused, unused, Duration.ZERO, Duration.ofMinutes(1), memory = memory))
    }

    @Test
    fun `the settings are read back as they were stored`() {
        val settings = SearchSettings(sensitivity = 0.75f, listening = false, backgroundThreshold = 0.1f, onsetVote = false, sortingModel = "ced-small")

        assertEquals(settings, SearchSettings.decode(settings.encode()))
        assertEquals(SearchSettings(), SearchSettings.decode(SearchSettings().encode()))
    }

    @Test
    fun `the high-pass has the sections SciPy's butter gives`() {
        val sections = HighPass(200.0, 16_000).sections
        // butter(4, 200, "high", fs=16000, output="sos"), each section normalised to a0 = 1.
        assertArrayEquals(doubleArrayOf(-1.8590762659582096, 0.8648248987672625), sections[0].copyOfRange(3, 5), 1e-9)
        assertArrayEquals(doubleArrayOf(-1.9357148371211979, 0.9417004516037268), sections[1].copyOfRange(3, 5), 1e-9)
        // The gains of both sections together are SciPy's 0.9024444568629439 of its first section.
        assertEquals(0.9024444568629439, sections[0][0] * sections[1][0], 1e-9)
    }

    /** Gives every clip the human score [score] in the class "Speech" and nothing else. */
    private class FakeTagger(val score: (FloatArray) -> Float) : SoundTagger {
        override val classNames = listOf("Speech", "Car")
        override fun tag(samples: FloatArray, sampleRate: Int) = floatArrayOf(score(samples), 0.5f)
    }

    private fun trackOf(speech: ByteArray): FeatureTrack {
        val records = ByteArray(speech.size * FeatureTrack.RECORD_BYTES)
        for (hop in speech.indices) records[hop * FeatureTrack.RECORD_BYTES + 3] = speech[hop]
        return FeatureTrack.fromRecords(records)
    }

    private companion object {
        const val HOPS_PER_SECOND = 50
    }
}
