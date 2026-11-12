package me.mupu.sleeptalkrecorder.data

import me.mupu.sleeptalkrecorder.detection.FoundBy
import me.mupu.sleeptalkrecorder.detection.SearchSettings
import me.mupu.sleeptalkrecorder.detection.Verdict
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration

/*
 * During a test phase, events are found in two ways side by side: the first (old) way is the
 * loudness detector alone, whose events are `EVENT` annotations; the second (new) way is the
 * `detection.SoundSearch`, whose events are `CANDIDATE` annotations. The sleep screen shows either
 * or a comparison of both. The first way's events and their transcripts stay exactly as they were.
 */

/** Which way's events the sleep screen shows. */
enum class EventWay {
    /** The first way: the loudness detector's events, as before the second way existed. */
    OLD,

    /** The second way: the sorted candidates of the sound search, background left out. */
    NEW,

    /** Both ways' events merged where they overlap, each with the system tag of the ways that found it. */
    COMPARISON,
}

/** In a comparison of both ways, which of them found an event. */
enum class WaysFound { BOTH, ONLY_OLD, ONLY_NEW }

/**
 * How the second way of finding events sorted one of its events: its [verdict], what [foundBy] it,
 * its human [score] (0 to 1) and the rise of its level at its start in dB.
 */
data class Sorting(val verdict: Verdict, val foundBy: FoundBy, val score: Float, val riseDb: Float) {

    /** The verdict, what found it and the rise as the annotation's text, such as `UNSURE BOTH 12.5`. */
    fun encode(): String = "${verdict.name} ${foundBy.name} ${"%.1f".format(java.util.Locale.ROOT, riseDb)}"

    companion object {
        /** The sorting an annotation's [text] and [score] ([AnnotationEntity.value]) state; null if they cannot be read. */
        fun decode(text: String?, score: Double?): Sorting? {
            val fields = text?.split(' ') ?: return null
            if (fields.size != 3 || score == null) return null
            return Sorting(
                verdict = Verdict.entries.find { it.name == fields[0] } ?: return null,
                foundBy = FoundBy.entries.find { it.name == fields[1] } ?: return null,
                score = score.toFloat(),
                riseDb = fields[2].toFloatOrNull() ?: return null,
            )
        }

        /** The order in which a verdict wins when several events are merged: human, then unsure, then background. */
        internal val Verdict.rank: Int
            get() = when (this) {
                Verdict.HUMAN -> 2
                Verdict.UNSURE -> 1
                Verdict.BACKGROUND -> 0
            }
    }
}

/**
 * The events of both ways as one list: the first way's [old] events and the second way's [new]
 * ones, each in order, merged where they overlap. Each merged event spans its members and takes
 * the id of its first member (the ids of both are unique annotation ids), the kind and words of its
 * member with the most voice (speech before murmur before noise), the best sorting of its members of
 * the second way, all their tags, hidden while one of them is, and [WaysFound] by which ways it holds.
 */
fun compareWays(old: List<SleepEvent>, new: List<SleepEvent>): List<SleepEvent> {
    val members = (old.map { it to true } + new.map { it to false }).sortedWith(compareBy({ it.first.start }, { it.first.end }))
    val groups = ArrayList<MutableList<Pair<SleepEvent, Boolean>>>()
    var end = Duration.ZERO
    for (member in members) {
        val group = groups.lastOrNull()
        if (group != null && member.first.start < end) {
            group += member
            end = maxOf(end, member.first.end)
        } else {
            groups += mutableListOf(member)
            end = member.first.end
        }
    }
    return groups.map { group ->
        val events = group.map { it.first }
        val ofOld = group.any { it.second }
        val ofNew = group.any { !it.second }
        val voiced = events.maxBy { voiceRank(it.kindId) }
        val sorting = group.filter { !it.second }.mapNotNull { it.first.sorting }
            .maxWithOrNull(compareBy<Sorting>({ with(Sorting) { it.verdict.rank } }, { it.score }))
        voiced.copy(
            id = events.first().id,
            start = events.first().start,
            end = events.maxOf { it.end },
            hidden = events.any { it.hidden },
            tagIds = events.flatMapTo(HashSet()) { it.tagIds },
            name = events.firstNotNullOfOrNull { it.name },
            sorting = sorting,
            ways = when {
                ofOld && ofNew -> WaysFound.BOTH
                ofOld -> WaysFound.ONLY_OLD
                else -> WaysFound.ONLY_NEW
            },
        )
    }
}

private fun voiceRank(kindId: String): Int = when (kindId) {
    EventKinds.SPEECH -> 2
    EventKinds.MURMUR -> 1
    else -> 0
}

/**
 * How far the second way searched a sleep ([AnnotationType.SEARCH]): with [settings], up to
 * [searchedTo] of its audio; [done] once it reached the end, or as far as the audio could be read,
 * which [partial] says. A [provisional] mark is that of the search during the night, whose
 * candidates the search after the night replaces part by part.
 */
data class SearchMark(
    val settings: SearchSettings,
    val searchedTo: Duration,
    val done: Boolean,
    val partial: Boolean = false,
    val provisional: Boolean = false,
) {

    /** The mark's text: the settings, then `partial` when the audio could not be read to its end and `provisional` during the night. */
    fun text(): String = settings.encode() + (if (partial) " $PARTIAL" else "") + if (provisional) " $PROVISIONAL" else ""

    companion object {
        private const val PARTIAL = "partial"
        private const val PROVISIONAL = "provisional"

        /** The mark a [SEARCH][AnnotationType.SEARCH] annotation states; null if it cannot be read. */
        fun of(annotation: AnnotationEntity): SearchMark? {
            val text = annotation.text ?: return null
            // The settings are the fields with a value; the flags follow them as single words.
            val settings = SearchSettings.decode(text) ?: return null
            val flags = text.split(' ').filter { '=' !in it }
            return SearchMark(settings, annotation.end, done = annotation.value == 1.0, partial = PARTIAL in flags, provisional = PROVISIONAL in flags)
        }
    }
}

/**
 * The windows of the listening pass from second [from] until [until] that a search during the night
 * heard itself ([AnnotationType.LISTENED]), of which those in [heard] (in order) heard a human sound
 * at the listening [threshold].
 */
data class ListenedWindows(val from: Int, val until: Int, val heard: List<Int>, val threshold: Float) {

    /** The windows that heard a human sound as runs of consecutive ones, such as `3605-3607 3610`. */
    fun text(): String {
        val runs = ArrayList<String>()
        var index = 0
        while (index < heard.size) {
            var end = index
            while (end + 1 < heard.size && heard[end + 1] == heard[end] + 1) end++
            runs += if (end == index) "${heard[index]}" else "${heard[index]}-${heard[end]}"
            index = end + 1
        }
        return runs.joinToString(" ")
    }

    companion object {
        /** The windows a [LISTENED][AnnotationType.LISTENED] annotation states; null if it cannot be read. */
        fun of(annotation: AnnotationEntity): ListenedWindows? {
            val threshold = annotation.value?.toFloat() ?: return null
            val heard = annotation.text.orEmpty().split(' ').filter { it.isNotEmpty() }.flatMap { run ->
                val ends = run.split('-').map { it.toIntOrNull() ?: return null }
                if (ends.size == 1) ends else (ends[0]..ends[1]).toList()
            }
            return ListenedWindows(annotation.start.seconds.toInt(), annotation.end.seconds.toInt(), heard, threshold)
        }
    }
}

/**
 * What storing a part of the second way's search changes among a sleep's annotations: the ids it
 * [removes] and the transcripts it [moves] onto another stretch, by id.
 */
data class SearchPartChanges(val removes: List<Long>, val moves: Map<Long, Stretch> = emptyMap())

/**
 * What a part of the second way's search from [from] changes among a sleep's [stored] annotations
 * when it is stored with its [mark] and the stretches of the candidates it [found]. It removes the
 * search mark and the earlier candidates that start in the part (until the mark's
 * [SearchMark.searchedTo], or until the end of the sleep once it is done), as those of an earlier
 * attempt at the part or of the search during the night; the candidates after the part stay until
 * their part is searched.
 *
 * Once the sleep is searched to its end, it removes the windows the night heard, and the phone's
 * transcripts of pieces ([piecesOf]) that no candidate holds any more, except one that only moved a
 * little: a transcript that overlaps exactly one piece without a result of its model, whose ends lie
 * within [MOVED_PIECE] of its own, moves onto that piece instead of being recognised again, since a
 * clip hears the padding around its piece anyway. Transcripts of pieces found again unchanged stay;
 * so do the user's marks, which keep to their stretches by overlap, and everything of the first way.
 */
fun searchPartChanges(stored: List<AnnotationEntity>, from: Duration, mark: SearchMark, found: List<Stretch>): SearchPartChanges {
    val sorting = stored.filter { it.origin == AnnotationOrigin.DEVICE && it.producer?.id == Producers.SORTING.id }
    val replaced = sorting.filter { annotation ->
        when (annotation.type) {
            AnnotationType.SEARCH -> true
            AnnotationType.CANDIDATE -> annotation.start >= from && (mark.done || annotation.start < mark.searchedTo)
            AnnotationType.LISTENED -> mark.done
            else -> false
        }
    }
    val ids = replaced.map { it.id }
    if (!mark.done) return SearchPartChanges(ids)
    val gone = ids.toHashSet()
    val kept = stored.filter { it.type == AnnotationType.CANDIDATE && it.id !in gone }.map { it.stretch } + found
    val pieces = kept.flatMap { piecesOf(it) }.distinct().sortedBy { it.start }
    val held = pieces.toHashSet()
    val transcripts = stored.filter { it.type == AnnotationType.CANDIDATE_TRANSCRIPT && it.origin != AnnotationOrigin.USER }
    // The pieces that have a result of a model, which a moved transcript of the same model would doubt.
    val taken = transcripts.filter { it.stretch in held }.mapTo(HashSet()) { it.stretch to (it.origin to it.producer?.id) }
    val removes = ArrayList(ids)
    val moves = LinkedHashMap<Long, Stretch>()
    for (transcript in transcripts) {
        if (transcript.stretch in held) continue
        val overlapping = pieces.filter { it.start < transcript.end && it.end > transcript.start }
        val target = overlapping.singleOrNull()?.takeIf { piece ->
            (piece.start - transcript.start).abs() <= MOVED_PIECE && (piece.end - transcript.end).abs() <= MOVED_PIECE
        }
        if (target != null && taken.add(target to (transcript.origin to transcript.producer?.id))) {
            moves[transcript.id] = target
        } else {
            removes += transcript.id
        }
    }
    return SearchPartChanges(removes, moves)
}

/** How far each end of a piece may move for its transcript to move with it ([searchPartChanges]). */
val MOVED_PIECE: Duration = Duration.ofMillis(100)

/** The longest stretch speech recognition hears of one of the second way's events at once. */
val LONGEST_PIECE: Duration = Duration.ofSeconds(60)

/**
 * The pieces speech recognition hears of a second way's event over [stretch]: the stretch itself,
 * or equal pieces of at most [LONGEST_PIECE] when it is longer, so memory stays bounded.
 */
fun piecesOf(stretch: Stretch): List<Stretch> {
    val millis = (stretch.end - stretch.start).toMillis()
    val count = ((millis + LONGEST_PIECE.toMillis() - 1) / LONGEST_PIECE.toMillis()).coerceAtLeast(1)
    return (0 until count).map { piece ->
        Stretch(stretch.start + Duration.ofMillis(millis * piece / count), stretch.start + Duration.ofMillis(millis * (piece + 1) / count))
    }
}

/** Whether this stretch lies wholly within one of [stretches]. */
fun Stretch.liesWithin(stretches: List<Stretch>): Boolean = stretches.any { it.start <= start && end <= it.end }

/**
 * The second way's [candidates] with the kind and words they show: those of the first way's event
 * they lie wholly within when that has a transcript (among [old], with their transcripts), which
 * is shown and not recognised again; else the results of their own pieces among [transcripts]
 * ([AnnotationType.CANDIDATE_TRANSCRIPT], per model joined in order, the most voiced kind winning),
 * under the user's transcripts among [user], which count for every event they overlap, as for the
 * first way's events.
 */
fun withCandidateTranscripts(
    candidates: List<SleepEvent>,
    old: List<SleepEvent>,
    transcripts: List<AnnotationEntity>,
    user: List<AnnotationEntity>,
    kinds: Map<String, EventKindEntity>,
    activeModel: String?,
): List<SleepEvent> {
    val oldWithTranscript = old.filter { it.transcriptOrigin != null }
    val byStretch = transcripts.groupBy { it.stretch }
    return candidates.map { candidate ->
        val holder = oldWithTranscript.firstOrNull { it.start <= candidate.start && candidate.end <= it.end }
        if (holder != null) {
            return@map candidate.copy(
                kindId = holder.kindId,
                kindName = holder.kindName,
                kindColor = holder.kindColor,
                transcript = holder.transcript,
                transcriptOrigin = holder.transcriptOrigin,
                transcriptProducer = holder.transcriptProducer,
                results = holder.results,
            )
        }
        // One result per model over the whole event, made of its pieces' results in order.
        val joined = piecesOf(candidate.stretch).flatMap { byStretch[it].orEmpty() }
            .groupBy { it.origin to it.producer?.id }
            .map { (_, pieces) ->
                pieces.last().copy(
                    id = pieces.maxOf { it.id },
                    type = AnnotationType.TRANSCRIPT,
                    start = candidate.start,
                    end = candidate.end,
                    kindId = pieces.maxBy { voiceRank(it.kindId ?: EventKinds.NOISE) }.kindId,
                    text = pieces.mapNotNull { it.text }.joinToString(" ").ifBlank { null },
                )
            }
        withTranscripts(listOf(candidate), joined + user, kinds, activeModel).single()
            .let { shown -> shown.copy(results = shown.results.map { it.copy(newWay = true) }) }
    }
}

/**
 * The first way's [old] events with the results of the second way's [candidates] that overlap them
 * added after their own results, as further results like those of other models: shown beside the
 * event's own transcripts, which still win, so the first way's events show what they showed before.
 * Candidates that only show a first way's transcript add nothing.
 */
fun withNewWayResults(old: List<SleepEvent>, candidates: List<SleepEvent>): List<SleepEvent> {
    val recognised = candidates.filter { candidate -> candidate.results.any { it.newWay } }
    if (recognised.isEmpty()) return old
    return old.map { event ->
        val added = recognised.filter { it.start < event.end && it.end > event.start }
            .flatMap { candidate -> candidate.results.filter { it.newWay } }
            .distinctBy { it.annotationId }
        if (added.isEmpty()) event else event.copy(results = event.results + added)
    }
}

/**
 * The events of the second way or of a comparison ([shown]) with [SleepEvent.sharesMarks] set
 * where an event overlaps more than one of the first way's [old] events, whose names, corrections
 * and hidden marks an edit of it could take away.
 */
fun withSharedMarks(shown: List<SleepEvent>, old: List<SleepEvent>): List<SleepEvent> = shown.map { event ->
    val overlapping = old.count { it.start < event.end && it.end > event.start }
    if (overlapping > 1) event.copy(sharesMarks = true) else event
}


/**
 * What the second way's search during the night found so far in the sleep being recorded: [events]
 * that are not background, in the first [searchedTo] of its audio.
 */
data class NightFindings(val events: Int, val searchedTo: Duration)
