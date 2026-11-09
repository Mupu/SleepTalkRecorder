package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.data.AnnotationOrigin
import me.mupu.sleeptalkrecorder.data.Producer
import me.mupu.sleeptalkrecorder.playback.Stretch

/** The audio around one event of a sleep, as an [EventAnalysis] receives it. */
class EventClip(
    val sleepId: Long,
    /** The event: its stretch of the sleep's audio. */
    val event: Stretch,
    /** Mono samples from -1 to 1, from a little before the event to a little after it. */
    val samples: FloatArray,
    /** Samples per second. */
    val sampleRate: Int,
)

/**
 * An analysis of single events: a clip of an event goes in, annotations about the event come out,
 * each with the analysis' [origin] and [producer]. The phone's speech recognition is
 * one; an analysis on a server the user chooses can be another beside it. It is closed once a run
 * has no more clips for it, which frees what it holds, such as a loaded model.
 */
interface EventAnalysis : AutoCloseable {
    /** Where the analysis runs: on the phone or on a server; its annotations replace only those of the same origin. */
    val origin: AnnotationOrigin

    /** Who makes the annotations, such as the model's id, in the version that changes whenever the same clip could give other annotations. */
    val producer: Producer

    /**
     * The annotations about [clip]'s event. Blocks while it works, so it is called off the main
     * thread.
     */
    suspend fun analyse(clip: EventClip): List<AnnotationEntity>

    override fun close() = Unit
}
