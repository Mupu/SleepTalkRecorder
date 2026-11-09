package me.mupu.sleeptalkrecorder.transcription

import android.util.Log
import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.playback.Stretch
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where speech recognition reads the events of sleeps and their audio, and keeps its results. */
interface TranscriptStore {
    /** The finished sleeps whose events are found and some of them wait for speech recognition, latest first. */
    suspend fun sleepsToTranscribe(): List<Long>

    /** The events of a sleep in order; with [onlyPending] only those without a result yet. */
    suspend fun eventsOf(sleepId: Long, onlyPending: Boolean): List<Stretch>

    /** The audio around [event]; without samples if its audio cannot be read. */
    suspend fun clip(sleepId: Long, event: Stretch): EventClip

    /** Keeps the [annotations] about [event] in place of the earlier ones of their analysis. */
    suspend fun store(sleepId: Long, event: Stretch, annotations: List<AnnotationEntity>)

    /** Whether a recording is running. */
    suspend fun isRecording(): Boolean
}

/** The battery's charge in percent and whether the phone is charging. */
data class BatteryState(val percent: Int, val charging: Boolean)

/** What a speech recognition run works on. */
sealed interface TranscriptionRequest {
    /**
     * Every event that waits for speech recognition, in all finished sleeps; with
     * [pauseOnLowBattery] the run stops while the battery is low and the phone does not charge.
     */
    data class Waiting(val pauseOnLowBattery: Boolean) : TranscriptionRequest

    /** All events of one sleep, again, also those with a result: the user asked for it. */
    data class Sleep(val sleepId: Long) : TranscriptionRequest
}

/** How far a run is: the sleep it works on and its events done in this run, of [total]. */
data class TranscriptionProgress(val sleepId: Long, val done: Int, val total: Int)

/**
 * Runs speech recognition over the events of sleeps, one event at a time, and keeps each result as
 * soon as it is there, so a run that is stopped continues where it stopped. Runs never overlap:
 * a second one waits until the first has finished, so only one model is ever in memory.
 */
class TranscriptionRunner(
    private val store: TranscriptStore,
    private val battery: () -> BatteryState,
    /** Told when the analysis fails on an event, which then waits for the next run. */
    private val onFailure: (message: String, error: Exception) -> Unit = { message, error -> Log.w(TAG, message, error) },
) {
    /** How it ends. */
    enum class Outcome {
        /** Every event it was asked for has a result. */
        FINISHED,

        /** No model is installed; the events wait for one. */
        NO_MODEL,

        /** It paused because the battery is low and the phone does not charge. */
        LOW_BATTERY,

        /** A recording runs; the events wait until it ends. */
        RECORDING,
    }

    private val running = Mutex()
    private val current = MutableStateFlow<TranscriptionProgress?>(null)

    /** The run in progress, or null. */
    val progress: StateFlow<TranscriptionProgress?> = current.asStateFlow()

    /**
     * Analyses the events of [request] with the analysis [open] loads, which it loads only if there
     * is work, and closes at the end; [open] gives null when no model is installed.
     */
    suspend fun run(request: TranscriptionRequest, open: suspend () -> EventAnalysis?): Outcome = running.withLock {
        var analysis: EventAnalysis? = null
        try {
            work(request) { analysis ?: open()?.also { analysis = it } }
        } finally {
            current.value = null
            analysis?.close()
        }
    }

    /** Works through [request], with the analysis [analysis] loads on first use. */
    private suspend fun work(request: TranscriptionRequest, analysis: suspend () -> EventAnalysis?): Outcome {
        // Events the analysis failed on wait for the next run, so this one does not try them forever.
        val failed = HashSet<Pair<Long, Stretch>>()
        while (true) {
            val (sleepId, events) = when (request) {
                is TranscriptionRequest.Sleep -> request.sleepId to store.eventsOf(request.sleepId, onlyPending = false)
                is TranscriptionRequest.Waiting -> {
                    if (store.isRecording()) return Outcome.RECORDING
                    nextWaiting(failed) ?: return Outcome.FINISHED
                }
            }
            if (events.isEmpty()) return Outcome.FINISHED
            val opened = analysis() ?: return Outcome.NO_MODEL

            current.value = TranscriptionProgress(sleepId, 0, events.size)
            for ((index, event) in events.withIndex()) {
                if (request is TranscriptionRequest.Waiting) {
                    if (request.pauseOnLowBattery && batteryLow()) return Outcome.LOW_BATTERY
                    if (store.isRecording()) return Outcome.RECORDING
                }
                if (!analyse(opened, sleepId, event)) failed += sleepId to event
                current.value = TranscriptionProgress(sleepId, index + 1, events.size)
            }
            if (request is TranscriptionRequest.Sleep) return Outcome.FINISHED
        }
    }

    /** The first sleep with waiting events that did not fail in this run, with those events. */
    private suspend fun nextWaiting(failed: Set<Pair<Long, Stretch>>): Pair<Long, List<Stretch>>? {
        for (sleepId in store.sleepsToTranscribe()) {
            val events = store.eventsOf(sleepId, onlyPending = true).filter { (sleepId to it) !in failed }
            if (events.isNotEmpty()) return sleepId to events
        }
        return null
    }

    /** Analyses one event and stores its result; false if the analysis failed. */
    private suspend fun analyse(analysis: EventAnalysis, sleepId: Long, event: Stretch): Boolean {
        val clip = store.clip(sleepId, event)
        val annotations = try {
            analysis.analyse(clip)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            onFailure("Speech recognition failed on an event of sleep $sleepId at ${event.start}", error)
            return false
        }
        store.store(sleepId, event, annotations)
        return true
    }

    private fun batteryLow(): Boolean = battery().let { !it.charging && it.percent < LOW_BATTERY_PERCENT }

    private companion object {
        const val TAG = "Transcription"

        /** Below this charge an automatic run pauses until the phone charges. */
        const val LOW_BATTERY_PERCENT = 30
    }
}
