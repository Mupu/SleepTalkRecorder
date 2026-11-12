package me.mupu.sleeptalkrecorder.data

import androidx.room.withTransaction
import me.mupu.sleeptalkrecorder.data.Producers.SORTING
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.transcription.EventClip
import me.mupu.sleeptalkrecorder.transcription.TranscriptStore

/**
 * Where speech recognition reads the second way's own events and keeps its results, apart from the
 * first way's ([SleepTranscripts]), whose transcripts it never touches.
 *
 * An event of the second way that lies wholly within a first way's event with a transcript of the
 * phone needs no run: it shows that event's transcript. Every other one is heard in its pieces
 * ([piecesOf], at most a minute each, so memory stays bounded), and each piece's result is kept as
 * an [AnnotationType.CANDIDATE_TRANSCRIPT] over exactly that piece. Only sleeps the second way
 * searched to their end are recognised.
 */
class CandidateTranscripts(
    private val database: SleepDatabase,
    /** The first way's store, whose clips are cut the same way. */
    private val first: SleepTranscripts,
) : TranscriptStore {

    private val dao = database.sleepDao()

    override suspend fun sleepsToTranscribe(): List<Long> =
        dao.sleepsSearched(SORTING.id, SORTING.version).filter { eventsOf(it, onlyPending = true).isNotEmpty() }

    override suspend fun eventsOf(sleepId: Long, onlyPending: Boolean): List<Stretch> {
        val transcribed = dao.transcribedEventStretches(sleepId)
        val pieces = dao.candidateStretches(sleepId).filterNot { it.liesWithin(transcribed) }.flatMap(::piecesOf).distinct()
        if (!onlyPending) return pieces
        val done = dao.candidateTranscripts(sleepId).filter { it.origin == AnnotationOrigin.DEVICE }.mapTo(HashSet()) { it.stretch }
        return pieces.filter { it !in done }
    }

    override suspend fun clip(sleepId: Long, event: Stretch): EventClip = first.clip(sleepId, event)

    override suspend fun store(sleepId: Long, event: Stretch, annotations: List<AnnotationEntity>) {
        database.withTransaction {
            // The events found anew meanwhile no longer hold this piece; the result has no event left.
            if (event !in eventsOf(sleepId, onlyPending = false)) return@withTransaction
            for ((origin, producer) in annotations.map { it.origin to it.producer?.id }.distinct()) {
                dao.deleteCandidateTranscripts(sleepId, event.start, event.end, origin, producer)
            }
            dao.insertAnnotations(annotations.map { it.copy(type = AnnotationType.CANDIDATE_TRANSCRIPT) })
        }
    }

    override suspend fun isRecording(): Boolean = dao.isRecording()
}
