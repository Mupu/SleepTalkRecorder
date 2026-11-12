package me.mupu.sleeptalkrecorder.detection

import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.HOP_MILLIS
import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.unsigned
import java.time.Duration
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlinx.coroutines.ensureActive

/** What found a candidate of the [SoundSearch]. */
enum class FoundBy {
    /** The loudness search alone. */
    LOUDNESS,

    /** The listening pass alone: a quiet sound the loudness search missed. */
    LISTENING,

    /** The loudness search, and the listening pass heard a human sound in it too. */
    BOTH,
}

/** How the [SoundSearch] sorted a candidate. */
enum class Verdict {
    /** Surely a human sound: its human score reaches [SoundSearch.SURE_HUMAN]. */
    HUMAN,

    /** Neither surely human nor surely background; a stronger model could decide it later. */
    UNSURE,

    /** Surely background: hardly human to the sorting model, and it swells slowly (the onset vote). */
    BACKGROUND,
}

/**
 * The settings of the [SoundSearch], with the defaults measured on the labelled test nights. They
 * are stored with each sleep's results ([encode]), because a change applies only to sleeps searched
 * afterwards.
 */
data class SearchSettings(
    /** The loudness search's sensitivity, from 0 to 1 ([DetectionParams.sensitivity]); the most sensitive by default. */
    val sensitivity: Float = 1f,
    /** Whether the listening pass runs and adds the quiet human sounds the loudness search missed. */
    val listening: Boolean = true,
    /** The human score of a 2 s window that makes the listening pass hear a sound. */
    val listeningThreshold: Float = 0.18f,
    /** Below this human score a candidate can be background. */
    val backgroundThreshold: Float = 0.06f,
    /** Whether a candidate is background only when the onset rule also calls it slow. */
    val onsetVote: Boolean = true,
    /** The id of the model that sorts the candidates, such as `ced-mini`. */
    val sortingModel: String = DEFAULT_SORTING_MODEL,
) {
    /** The settings as a short text, such as `s=1.0 l=0.18 b=0.06 o=1 m=ced-mini` (no listening: `l=off`). */
    fun encode(): String =
        "s=$sensitivity l=${if (listening) listeningThreshold.toString() else "off"} b=$backgroundThreshold o=${if (onsetVote) 1 else 0} m=$sortingModel"

    companion object {
        /** The model that sorts candidates unless the settings name another. */
        const val DEFAULT_SORTING_MODEL = "ced-mini"

        /** The settings [text] holds, as [encode] wrote them; null if it cannot be read. */
        fun decode(text: String): SearchSettings? {
            val fields = text.split(' ').mapNotNull { field -> field.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val listening = fields["l"] ?: return null
            return SearchSettings(
                sensitivity = fields["s"]?.toFloatOrNull() ?: return null,
                listening = listening != "off",
                listeningThreshold = if (listening == "off") SearchSettings().listeningThreshold else listening.toFloatOrNull() ?: return null,
                backgroundThreshold = fields["b"]?.toFloatOrNull() ?: return null,
                onsetVote = fields["o"] == "1",
                sortingModel = fields["m"] ?: return null,
            )
        }
    }
}

/** A stretch of a sleep the [SoundSearch] found, before it was sorted. */
data class Candidate(
    /** Its hops in the sleep's feature track. */
    val hops: HopRange,
    val foundBy: FoundBy,
    /** The largest rise of the level at its start, in dB ([OnsetRule.rise]). */
    val riseDb: Float,
    /** Whether the onset rule calls it sudden; a sudden candidate is never background while the onset vote is on. */
    val sudden: Boolean,
)

/** A [candidate] with the human score the sorting model gave it and the [verdict] that follows. */
data class SortedCandidate(val candidate: Candidate, val score: Float, val verdict: Verdict)

/** A sleep's audio as the [SoundSearch] reads it: samples from -1 to 1 by their index from the sleep's start. */
interface SleepSamples {
    /** Samples per second. */
    val sampleRate: Int

    /** How many samples the sleep has. */
    val length: Long

    /** The [count] samples from [from] on, fewer at the end. */
    fun read(from: Long, count: Int): FloatArray
}

/**
 * The second way of finding events, run after the night: it searches more eagerly than the
 * loudness detector alone and sorts what it finds instead of listing everything.
 *
 * 1. **Loudness**: the [EventDetector] at [SearchSettings.sensitivity] finds candidates; each of
 *    its stretches is judged by the [OnsetRule], and a candidate is sudden when one of them is.
 * 2. **Listening**: a small tagger hears the whole sleep in 2 s windows a second apart, high-passed
 *    at 200 Hz so rumble cannot mask a voice. Windows whose [HumanScore] reaches the listening
 *    threshold are joined; those that overlap no loudness candidate become candidates of their own,
 *    which catches whispers and moans below the loudness threshold. A loudness candidate the
 *    listening pass heard is found by both.
 * 3. **Sorting**: each candidate's clip ([clipOf]) is brought to a common level and tagged as it is
 *    and high-passed at 200 and 400 Hz; its score is the highest human score of the three. A
 *    candidate is background when the score is below the background threshold and (with the onset
 *    vote) it swells slowly; human when the score reaches [SURE_HUMAN]; unsure otherwise.
 *
 * Nothing is deleted: background candidates stay, sorted, so the user can still listen to them.
 * The steps and numbers follow the prototype the settings were measured with.
 */
class SoundSearch(private val settings: SearchSettings, private val onset: OnsetRule = OnsetRule()) {

    /** The loudness search over the sleep's [track], which every part of a search reads. */
    fun loudness(track: SleepTrack): Detection = EventDetector.detect(track.track, DetectionParams(sensitivity = settings.sensitivity))

    /**
     * Searches the sleep of [track] and [samples] in parts of [partLength], one after the other,
     * as the app does it stored part by part; the parts give the same candidates as a search that
     * resumes after any of them.
     */
    suspend fun search(
        track: SleepTrack,
        samples: SleepSamples,
        listener: SoundTagger?,
        sorter: SoundTagger,
        partLength: Duration = PART_LENGTH,
    ): List<SortedCandidate> {
        val detection = loudness(track)
        val duration = track.positionOf(track.track.size)
        val found = ArrayList<SortedCandidate>()
        var from = Duration.ZERO
        while (from < duration) {
            found += searchPart(track, detection, samples, listener, sorter, from, from + partLength)!!
            from += partLength
        }
        return found
    }

    /**
     * Searches the part of the sleep from [from] until [until] (whole seconds): the candidates that
     * start there, sorted, in order. The loudness search's [detection] covers the whole sleep; the
     * listening pass with [listener] (null when it is off) hears this part and as much around it as
     * the candidates that start here reach, so a part gives the same candidates whether the parts
     * before it were searched in this run or another. The candidates are sorted with [sorter].
     * [onProgress] hears the share of the part done from 0 to 1; [shouldStop] is asked now and
     * then, and a true answer gives null. Blocks while the taggers run, so it is called off the
     * main thread.
     *
     * @throws java.io.IOException when the audio cannot be read.
     */
    suspend fun searchPart(
        track: SleepTrack,
        detection: Detection,
        samples: SleepSamples,
        listener: SoundTagger?,
        sorter: SoundTagger,
        from: Duration,
        until: Duration,
        onProgress: (Float) -> Unit = {},
        shouldStop: suspend () -> Boolean = { false },
    ): List<SortedCandidate>? {
        val owned = detection.events.filter { track.positionOf(it.start) >= from && track.positionOf(it.start) < until }
        val first = from.seconds.toInt()
        val last = until.seconds.toInt()
        val reach = owned.maxOfOrNull { ceilSeconds(track.positionOf(it.end)) } ?: 0
        val windows = if (listener != null && settings.listening) windowCount(samples.length, samples.sampleRate) else 0
        // A candidate's clip with three views takes about as long as a dozen 2 s windows.
        val work = { candidates: Int -> (last - first) + CANDIDATE_WORK * candidates.coerceAtLeast(1) }

        // The windows from two before the part on, which tell whether a stretch heard at its
        // start began before it, and on past its end as far as a stretch or an event goes on.
        val heard = ArrayList<Int>()
        if (windows > 0 && listener != null) {
            val human = HumanScore(listener.classNames)
            val rate = samples.sampleRate
            val start = (first - 2).coerceAtLeast(0)
            val filter = HighPass(LISTENING_CUTOFF_HZ, rate)
            // The filter settles on the second before, so a window sounds the same in every run.
            if (start > 0) filter.filter(samples.read((start - 1L) * rate, rate))
            var previous = filter.filter(samples.read(start.toLong() * rate, rate))
            var window = start
            while (window < windows) {
                val goesOn = heard.isNotEmpty() && heard.last() >= window - 2
                if (window >= maxOf(last, reach) && (!goesOn || window >= maxOf(last + LONGEST_LOOK_AHEAD, reach))) break
                coroutineContext.ensureActive()
                if ((window - start) % CHECK_EVERY_WINDOWS == 0) {
                    if (shouldStop()) return null
                    onProgress((window - first).coerceAtLeast(0).toFloat() / work(owned.size))
                }
                val next = filter.filter(samples.read((window + 1L) * rate, rate))
                if (human.of(listener.tag(previous + next, rate)) >= settings.listeningThreshold) heard += window
                previous = next
                window++
            }
        }

        val scores = FloatArray((heard.maxOrNull() ?: -1) + 1)
        for (window in heard) scores[window] = 1f
        val spans = listenedSpans(scores, threshold = 1f)
        val listened = spans.map { (start, end) -> track.hopsOf(Duration.ofSeconds(start.toLong()), Duration.ofSeconds(end.toLong())) }
        // A stretch heard belongs to the part its first window starts in.
        val ownedHeard = spans.indices.filter { spans[it].first in first until last }.map { listened[it] }.toSet()
        val candidates = candidates(detection, listened).filter { candidate ->
            if (candidate.foundBy == FoundBy.LISTENING) candidate.hops in ownedHeard else candidate.hops in owned
        }

        val human = HumanScore(sorter.classNames)
        val duration = Duration.ofNanos(samples.length * NANOS_PER_SECOND / samples.sampleRate)
        return candidates.mapIndexed { index, candidate ->
            coroutineContext.ensureActive()
            if (index % CHECK_EVERY_CANDIDATES == 0) {
                if (shouldStop()) return null
                onProgress(((last - first) + CANDIDATE_WORK * index).toFloat() / work(candidates.size))
            }
            val (clipFrom, clipUntil) = clipOf(candidate, track, duration)
            val clipFirst = clipFrom.toNanos() * samples.sampleRate / NANOS_PER_SECOND
            val clipLast = clipUntil.toNanos() * samples.sampleRate / NANOS_PER_SECOND
            val clip = samples.read(clipFirst, (clipLast - clipFirst).toInt())
            val score = viewsOf(clip, samples.sampleRate).maxOf { human.of(sorter.tag(it, samples.sampleRate)) }
            SortedCandidate(candidate, score, verdict(score, candidate.sudden))
        }.also { onProgress(1f) }
    }

    private fun ceilSeconds(position: Duration): Int = ((position.toMillis() + 999) / 1000).toInt()

    /**
     * The candidates of the loudness search's [detection] and of the [listened] stretches (in hops),
     * in order: every loudness event, found by both where a listened stretch overlaps it, and every
     * listened stretch that overlaps no event.
     */
    fun candidates(detection: Detection, listened: List<HopRange>): List<Candidate> {
        val found = detection.events.map { event ->
            val rise = detection.stretches.filter { it.overlaps(event) }.maxOfOrNull { onset.rise(detection.level, it) } ?: 0f
            Candidate(
                event,
                if (listened.any { it.overlaps(event) }) FoundBy.BOTH else FoundBy.LOUDNESS,
                rise,
                sudden = rise >= onset.minRiseDb,
            )
        }
        val added = listened.filter { span -> detection.events.none { it.overlaps(span) } }.map { span ->
            val rise = onset.rise(detection.level, span)
            Candidate(span, FoundBy.LISTENING, rise, sudden = rise >= onset.minRiseDb)
        }
        return (found + added).sortedBy { it.hops.start }
    }

    /**
     * How [score] and the onset rule's judgement sort a candidate: background below the background
     * threshold, unless the onset vote is on and it started [sudden]; human from [SURE_HUMAN] on;
     * unsure in between.
     */
    fun verdict(score: Float, sudden: Boolean): Verdict = when {
        score < settings.backgroundThreshold && !(settings.onsetVote && sudden) -> Verdict.BACKGROUND
        score >= SURE_HUMAN -> Verdict.HUMAN
        else -> Verdict.UNSURE
    }

    companion object {
        /** Identifies the search's rules in stored results; raise it when they change. */
        const val VERSION = "1"

        /** From this human score on a candidate is surely human. */
        const val SURE_HUMAN = 0.5f

        /** The listening pass hears the audio high-passed here, in Hz. */
        const val LISTENING_CUTOFF_HZ = 200.0

        /** The sorting model also hears each clip high-passed at these frequencies, in Hz. */
        val SORTING_CUTOFFS_HZ = doubleArrayOf(200.0, 400.0)

        /** The level every clip is brought to before it is sorted, as RMS in dBFS. */
        const val CLIP_LEVEL_DBFS = -25.0

        /** The listening windows are this many seconds long and start a second apart. */
        const val WINDOW_SECONDS = 2

        /** How much of the sound before and after a candidate its clip holds. */
        val CLIP_PADDING: Duration = Duration.ofMillis(500)

        /** A candidate longer than this is sorted by its loudest stretch of this length. */
        val LONGEST_CORE: Duration = Duration.ofSeconds(9)

        /** The part of a sleep a search stores at once, so a search that is stopped loses at most one. */
        val PART_LENGTH: Duration = Duration.ofMinutes(15)

        /** How far past its part a stretch heard by the listening pass is followed at most, in seconds. */
        const val LONGEST_LOOK_AHEAD = 300

        /** The shortest clip the sorting model gets; shorter ones are filled up with silence. */
        val SHORTEST_CLIP: Duration = Duration.ofMillis(500)

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val CANDIDATE_WORK = 12
        private const val CHECK_EVERY_WINDOWS = 60
        private const val CHECK_EVERY_CANDIDATES = 5

        /** The 2 s windows a second apart that fit wholly into [length] samples; none in less than 2 s. */
        fun windowCount(length: Long, sampleRate: Int): Int =
            if (length < WINDOW_SECONDS.toLong() * sampleRate) 0 else ((length - WINDOW_SECONDS.toLong() * sampleRate) / sampleRate + 1).toInt()

        /**
         * The stretches the listening pass heard, from the human [scores] of the windows (window k
         * starts at second k): each window at [threshold] or more covers its 2 s, and windows that
         * overlap or touch are joined. Each is a pair of the first second and the second after it.
         */
        fun listenedSpans(scores: FloatArray, threshold: Float): List<Pair<Int, Int>> {
            val spans = ArrayList<Pair<Int, Int>>()
            for (window in scores.indices) {
                if (scores[window] < threshold) continue
                val last = spans.lastOrNull()
                if (last != null && window <= last.second) {
                    spans[spans.lastIndex] = last.first to window + WINDOW_SECONDS
                } else {
                    spans += window to window + WINDOW_SECONDS
                }
            }
            return spans
        }

        /**
         * The stretch of the sleep's audio the sorting model hears for [candidate]: the candidate,
         * or its loudest [LONGEST_CORE] in the speech band when it is longer, with [CLIP_PADDING]
         * before and after it, held within the sleep's [duration].
         */
        fun clipOf(candidate: Candidate, track: SleepTrack, duration: Duration): Pair<Duration, Duration> {
            var core = candidate.hops
            val longest = (LONGEST_CORE.toMillis() / HOP_MILLIS).toInt()
            if (core.end - core.start > longest) {
                val speech = track.track.speech
                var sum = 0.0
                for (hop in core.start until core.start + longest) sum += power(speech[hop])
                var best = sum
                var bestStart = core.start
                for (start in core.start + 1..core.end - longest) {
                    sum += power(speech[start + longest - 1]) - power(speech[start - 1])
                    if (sum > best) {
                        best = sum
                        bestStart = start
                    }
                }
                core = HopRange(bestStart, bestStart + longest)
            }
            val start = (track.positionOf(core.start) - CLIP_PADDING).coerceAtLeast(Duration.ZERO)
            val end = (track.positionOf(core.end) + CLIP_PADDING).coerceAtMost(duration)
            return start to maxOf(start, end)
        }

        /**
         * The views the sorting model hears of [clip]: brought to [CLIP_LEVEL_DBFS] (or as loud as
         * it goes without clipping) after short clips were filled up to [SHORTEST_CLIP], as it is
         * and high-passed at each of [SORTING_CUTOFFS_HZ].
         */
        fun viewsOf(clip: FloatArray, sampleRate: Int): List<FloatArray> {
            val shortest = (SHORTEST_CLIP.toMillis() * sampleRate / 1000).toInt()
            val padded = if (clip.size < shortest) clip.copyOf(shortest) else clip
            val levelled = levelled(padded)
            return listOf(levelled) + SORTING_CUTOFFS_HZ.map { HighPass(it, sampleRate).filter(levelled) }
        }

        /** [samples] scaled to [CLIP_LEVEL_DBFS] RMS, but never beyond a peak of 0.99. */
        fun levelled(samples: FloatArray): FloatArray {
            var sum = 0.0
            var peak = 0.0
            for (sample in samples) {
                sum += sample.toDouble() * sample
                peak = max(peak, kotlin.math.abs(sample.toDouble()))
            }
            val rms = sqrt(sum / max(1, samples.size)) + TINY
            val gain = min(10.0.pow(CLIP_LEVEL_DBFS / 20) / rms, 0.99 / (peak + TINY)).toFloat()
            return FloatArray(samples.size) { samples[it] * gain }
        }

        private const val TINY = 1e-9

        /** The power of a speech-band level code; digital silence has none. */
        private fun power(code: Byte): Double =
            if (unsigned(code) == 0) 0.0 else 10.0.pow(FeatureTrack.levelDb(code) / 10.0)
    }
}
