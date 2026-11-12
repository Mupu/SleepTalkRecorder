package me.mupu.sleeptalkrecorder.data

/**
 * The [events] with the kind and words of their winning transcript among [transcripts], with the
 * name and colour of that kind from [kinds], and the results of every analysis over them; an event
 * without a transcript keeps the detector's kind.
 *
 * The user's transcript wins over a server's, which wins over the phone's. Each model keeps its
 * own result, so an event recognised again with another model has a result of each: of the same
 * origin the one of the [activeModel] wins, else the latest. An analysis' transcript belongs to the
 * event over exactly its stretch, because it describes that event's clip, and a new search for
 * events removes the phone's transcripts of stretches that are no longer an event. The user's
 * corrections and downgrades instead count for every event they overlap, like hidden marks: a
 * search with other settings finds the event again with slightly different bounds, and the
 * correction still keeps to it, also after the events were recognised again with another model.
 */
fun withTranscripts(
    events: List<SleepEvent>,
    transcripts: List<AnnotationEntity>,
    kinds: Map<String, EventKindEntity>,
    activeModel: String?,
): List<SleepEvent> {
    val (user, analyses) = transcripts.partition { it.origin == AnnotationOrigin.USER }
    val order = compareBy<AnnotationEntity> { it.origin.rank }
        .thenBy { it.producer?.id != activeModel }
        .thenByDescending { it.id }
    val byStretch = analyses.groupBy { it.stretch }.mapValues { (_, over) -> over.sortedWith(order) }

    return events.map { event ->
        val results = byStretch[event.stretch].orEmpty()
        // The user's annotations are few, one per event they changed, so they are searched in full.
        val winner = user.filter { it.start < event.end && it.end > event.start }.maxByOrNull { it.id }
            ?: results.firstOrNull()
            ?: return@map event
        val kind = winner.kindId?.let(kinds::get)
        event.copy(
            kindId = kind?.id ?: event.kindId,
            kindName = if (kind != null) kind.name else event.kindName,
            kindColor = kind?.color ?: event.kindColor,
            transcript = winner.text,
            transcriptOrigin = winner.origin,
            transcriptProducer = winner.producer?.id,
            results = results.map { TranscriptResult(it.id, it.origin, it.producer?.id, it.kindId ?: EventKinds.NOISE, it.text) },
        )
    }
}

/**
 * Whether marking [event] as speech by hand needs a transcript of the user's own: not when the
 * transcripts of analyses among [transcripts] already make it a speech event, because their words
 * show again once the user's own transcripts over it are gone; but when they make it a murmur.
 */
fun needsUserSpeechMark(
    event: SleepEvent,
    transcripts: List<AnnotationEntity>,
    kinds: Map<String, EventKindEntity>,
    activeModel: String?,
): Boolean {
    val analyses = transcripts.filter { it.origin != AnnotationOrigin.USER }
    return !withTranscripts(listOf(event.copy(kindId = EventKinds.NOISE)), analyses, kinds, activeModel).single().isSpeech
}

/** The order in which the transcripts of analyses win: a server's before the phone's. */
private val AnnotationOrigin.rank: Int
    get() = when (this) {
        AnnotationOrigin.USER -> 0
        AnnotationOrigin.SERVER -> 1
        AnnotationOrigin.DEVICE -> 2
    }

/**
 * The finished sleeps of [summaries] whose events are found, in their order, each with its
 * [recordings] and its [events] with their winning [transcripts]: the readings of all sleeps at
 * once, as the stats count them, put together by sleep, with the [activeModel]'s results winning.
 */
fun storedSleeps(
    summaries: List<SleepSummary>,
    recordings: List<RecordingEntity>,
    events: List<SleepEventOf>,
    transcripts: List<AnnotationEntity>,
    kinds: List<EventKindEntity>,
    activeModel: String?,
): List<StoredSleep> {
    val recordingsBySleep = recordings.groupBy { it.sleepId }
    val eventsBySleep = events.groupBy({ it.sleepId }, { it.event })
    val transcriptsBySleep = transcripts.groupBy { it.sleepId }
    val kindsById = kinds.associateBy { it.id }
    return summaries.filter { !it.isRunning && it.eventsFound }.map { summary ->
        StoredSleep(
            summary,
            recordingsBySleep[summary.id].orEmpty(),
            withTranscripts(eventsBySleep[summary.id].orEmpty(), transcriptsBySleep[summary.id].orEmpty(), kindsById, activeModel),
        )
    }
}
