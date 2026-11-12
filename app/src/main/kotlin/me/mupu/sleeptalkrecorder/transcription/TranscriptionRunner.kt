package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

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

/**
 * The second way of finding events, which runs in the same runs as speech recognition but only once
 * the first way's events have nothing left to recognise, under the same rules (battery, recordings,
 * the user's pauses). A search stores its results part by part, so one that is stopped goes on
 * where it stopped.
 */
interface EventSearch {
    /** The finished sleeps it has not searched to their end yet, the latest first. */
    suspend fun sleepsToSearch(): List<Long>

    /**
     * Searches the sleep [sleepId] from where it stopped, or from its start again with [again],
     * storing each part as soon as it is searched; it tells [onProgress] the share done from 0 to
     * 1 and asks [shouldStop] now and then.
     */
    suspend fun search(sleepId: Long, again: Boolean, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean): SearchOutcome

    /**
     * Searches the sleep [sleepId] while it is recorded, from where the search during the night
     * got, as far as the recording makes its results final, storing each part as soon as it is
     * searched; DONE also when no part is final yet. It tells [onProgress] the share of the part
     * done from 0 to 1 and asks [shouldStop] now and then.
     *
     * @throws ModelUnusable when a sound model cannot be loaded, which no later run that night changes.
     */
    suspend fun searchNight(sleepId: Long, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean): SearchOutcome
}

/** A model is installed but cannot be loaded, for example because its native runtime refused it. */
class ModelUnusable(message: String, cause: Throwable) : Exception(message, cause)

/** How a search of the second way ended. */
enum class SearchOutcome {
    /** The sleep is searched to its end, or as far as its audio could be read. */
    DONE,

    /** A model it needs is not installed; no sleep can be searched until it is. */
    NO_MODEL,

    /** It was asked to stop; the parts searched so far are kept. */
    STOPPED,

    /** It failed on this sleep, which is tried again in a later run. */
    FAILED,
}

/**
 * How far the second way is with a sleep: [searching] it, [done] percent of 100, or recognising
 * speech in its own events, [done] of [total].
 */
data class NewWayProgress(val sleepId: Long, val searching: Boolean, val done: Int, val total: Int)

/** The battery's charge in percent and whether the phone is charging. */
data class BatteryState(val percent: Int, val charging: Boolean)

/** What a speech recognition run works on. */
sealed interface TranscriptionRequest {
    /**
     * Every event that waits for speech recognition, in all finished sleeps; with
     * [pauseOnLowBattery] the run stops while the battery is low and the phone does not charge.
     */
    data class Waiting(val pauseOnLowBattery: Boolean) : TranscriptionRequest

    /**
     * The events of one sleep from the one at [from] on: all of them again, also those with a
     * result, because the user asked for it; with [onlyPending] only those without a result. Then
     * the second way searches the sleep if it has not to its end, or from its start again with
     * [searchAgain], and its own events that wait are recognised.
     */
    data class Sleep(
        val sleepId: Long,
        val from: Duration = Duration.ZERO,
        val onlyPending: Boolean = false,
        val searchAgain: Boolean = false,
    ) : TranscriptionRequest

    /** One event of a sleep, again: the user asked for it, to compare the result of another model. */
    data class Event(val sleepId: Long, val event: Stretch) : TranscriptionRequest

    /**
     * The sleep [sleepId] while it is recorded: the second way searches what is recorded so far as
     * far as it is final ([EventSearch.searchNight]), and its own events that wait are recognised;
     * the first way finds its events only after the stop. It runs while [mayRun] says yes (the
     * conditions of the scan during the night) and the recording goes on. [failures] counts, over
     * the night's runs, how often the analysis failed on each piece; one that failed
     * [TranscriptionRunner.NIGHT_ATTEMPTS] times waits for the run after the night.
     */
    class Night(
        val sleepId: Long,
        val failures: MutableMap<Stretch, Int> = HashMap(),
        val mayRun: suspend () -> Boolean,
    ) : TranscriptionRequest {
        override fun toString() = "Night(sleepId=$sleepId)"
    }
}

/** How far a run is: the sleep it works on and its events done in this run, of [total]. */
data class TranscriptionProgress(val sleepId: Long, val done: Int, val total: Int)

/**
 * Runs speech recognition over the events of sleeps, one event at a time, and keeps each result as
 * soon as it is there, so a run that is stopped continues where it stopped. Runs never overlap:
 * a second one waits until the first has finished, so only one speech model is ever in memory.
 * Sleeps the user paused are left out from their next event on.
 *
 * A single event the user asked for ([TranscriptionRequest.Event]) goes first: a run in progress
 * gives way before its next event, closing its speech model, or at the next check of its search,
 * whose small sound models stay loaded; once no such event waits, the run loads its model again
 * and goes on where it was, leaving out what got a result meanwhile.
 *
 * The first way's events ([store]) come first, exactly as without the second way. Only when none of
 * them waits does the second way's [search] search the sleeps it has not searched to their end,
 * and then the second way's own events ([candidates]) that wait are recognised. A run during the
 * night ([TranscriptionRequest.Night]) does only the latter two, for the sleep being recorded.
 */
class TranscriptionRunner(
    private val store: TranscriptStore,
    private val pauses: PauseStore,
    private val battery: () -> BatteryState,
    /** The second way of finding events; null where it does not run. */
    private val search: EventSearch? = null,
    /** The second way's own events that speech recognition hears; null where it does not run. */
    private val candidates: TranscriptStore? = null,
    /** Told when the analysis fails on an event, which then waits for the next run. */
    private val onFailure: (message: String, error: Exception) -> Unit,
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

        /** The analysis failed on the one event it was asked for. */
        FAILED,
        /** The user paused the sleep it was asked for. */
        PAUSED,

        /** A run during the night stopped because its conditions no longer hold (battery, charger, settings). */
        NOT_ALLOWED,
    }

    /** Held by a run over events of sleeps for its whole length, so these runs never overlap. */
    private val running = Mutex()

    /** Held by whatever recognises speech now: a run, or a single event while the run gives way. */
    private val turn = Mutex()

    /** The single events that wait for their [turn] or have it. */
    private val singles = MutableStateFlow(0)

    private val current = MutableStateFlow<TranscriptionProgress?>(null)

    private val newWay = MutableStateFlow<NewWayProgress?>(null)

    /** The run over the first way's events in progress, or null. */
    val progress: StateFlow<TranscriptionProgress?> = current.asStateFlow()

    /** The second way's work in progress, its search or its own speech recognition, or null. */
    val newWayProgress: StateFlow<NewWayProgress?> = newWay.asStateFlow()

    /**
     * Analyses the events of [request] with the analysis [open] loads, which it loads only if there
     * is work, and closes at the end; [open] gives null when no model is installed. Then, unless
     * the request was for one event, the second way searches and its events are recognised. A
     * request for one event goes before a run in progress and reports no progress.
     */
    suspend fun run(request: TranscriptionRequest, open: suspend () -> EventAnalysis?): Outcome {
        if (request is TranscriptionRequest.Event) return recogniseSingle(request, open)
        return running.withLock { turn.withLock { runEvents(request, open) } }
    }

    private suspend fun recogniseSingle(request: TranscriptionRequest.Event, open: suspend () -> EventAnalysis?): Outcome {
        singles.update { it + 1 }
        try {
            return turn.withLock { recognise(store, request, open) {} }
        } finally {
            singles.update { it - 1 }
        }
    }

    /**
     * Lets the single events the user asked for go first, if one waits: [release] frees what the run
     * holds, and the run waits until none is left. Returns whether it gave way.
     */
    private suspend fun giveWay(release: () -> Unit = {}): Boolean {
        if (singles.value == 0) return false
        release()
        turn.unlock()
        try {
            singles.first { it == 0 }
        } finally {
            // The run releases its turn at its end also when it is stopped meanwhile.
            withContext(NonCancellable) { turn.lock() }
        }
        return true
    }

    private suspend fun runEvents(request: TranscriptionRequest, open: suspend () -> EventAnalysis?): Outcome {
        return try {
            if (request is TranscriptionRequest.Night) return runNight(request, open)
            val first = recognise(store, request, open) { current.value = it }
            // A model that is missing holds back nothing the second way's search needs.
            if (first != Outcome.FINISHED && first != Outcome.NO_MODEL) return first
            searchNewWay(request)?.let { return it }
            val own = candidates ?: return first
            val second = when (request) {
                is TranscriptionRequest.Sleep -> TranscriptionRequest.Sleep(request.sleepId, onlyPending = true)
                else -> request
            }
            val outcome = recognise(own, second, open) { progress ->
                newWay.value = progress?.let { NewWayProgress(it.sleepId, searching = false, done = it.done, total = it.total) }
            }
            if (first == Outcome.NO_MODEL && outcome == Outcome.FINISHED) first else outcome
        } finally {
            current.value = null
            newWay.value = null
        }
    }

    /** The second way's search of the sleep being recorded, then speech recognition of its waiting events. */
    private suspend fun runNight(request: TranscriptionRequest.Night, open: suspend () -> EventAnalysis?): Outcome {
        val search = search ?: return Outcome.FINISHED
        val own = candidates ?: return Outcome.FINISHED
        stopReason(request, request.sleepId)?.let { return it }
        val searched = try {
            search.searchNight(
                request.sleepId,
                onProgress = { share -> newWay.value = NewWayProgress(request.sleepId, searching = true, done = (share * PERCENT).toInt(), total = PERCENT) },
                shouldStop = { giveWay(); stopReason(request, request.sleepId) != null },
            )
        } finally {
            newWay.value = null
        }
        when (searched) {
            SearchOutcome.NO_MODEL, SearchOutcome.FAILED -> return Outcome.FINISHED
            SearchOutcome.STOPPED -> return stopReason(request, request.sleepId) ?: Outcome.FINISHED
            SearchOutcome.DONE -> Unit
        }
        return recognise(own, request, open) { progress ->
            newWay.value = progress?.let { NewWayProgress(it.sleepId, searching = false, done = it.done, total = it.total) }
        }
    }

    /**
     * Recognises the events of [request] in [store] with the analysis [open] loads on first use,
     * which it closes at the end, telling [report] how far it is.
     */
    private suspend fun recognise(
        store: TranscriptStore,
        request: TranscriptionRequest,
        open: suspend () -> EventAnalysis?,
        report: (TranscriptionProgress?) -> Unit,
    ): Outcome {
        val analysis = LoadedAnalysis(open)
        try {
            return work(store, request, report, analysis)
        } finally {
            report(null)
            analysis.close()
        }
    }

    /** Works through [request] in [store], with the [analysis] loaded on first use. */
    private suspend fun work(
        store: TranscriptStore,
        request: TranscriptionRequest,
        report: (TranscriptionProgress?) -> Unit,
        analysis: LoadedAnalysis,
    ): Outcome {
        // Events the analysis failed on wait for the next run, so this one does not try them forever.
        val failed = HashSet<Pair<Long, Stretch>>()
        while (true) {
            val (sleepId, events) = when (request) {
                is TranscriptionRequest.Sleep ->
                    request.sleepId to store.eventsOf(request.sleepId, request.onlyPending).filter { it.start >= request.from }
                // Events found anew meanwhile may no longer hold the stretch; then nothing is left to do.
                is TranscriptionRequest.Event ->
                    request.sleepId to store.eventsOf(request.sleepId, onlyPending = false).filter { it == request.event }
                is TranscriptionRequest.Waiting -> {
                    if (store.isRecording()) return Outcome.RECORDING
                    nextWaiting(store, failed) ?: return Outcome.FINISHED
                }
                is TranscriptionRequest.Night -> request.sleepId to store.eventsOf(request.sleepId, onlyPending = true)
                    .filter { (request.sleepId to it) !in failed && (request.failures[it] ?: 0) < NIGHT_ATTEMPTS }
            }
            if (events.isEmpty()) return Outcome.FINISHED
            analysis.get() ?: return Outcome.NO_MODEL

            // Events without a result keep waiting on their own; a run over all events remembers
            // where it is, so one that is paused or stopped goes on there instead of starting again.
            val rerun = request is TranscriptionRequest.Sleep && !request.onlyPending

            report(TranscriptionProgress(sleepId, 0, events.size))
            // The events still without a result after the run gave way to single events, which may have recognised some.
            var stillWaiting: Set<Stretch>? = null
            for ((index, event) in events.withIndex()) {
                if (request !is TranscriptionRequest.Event && giveWay(analysis::close) && !rerun) {
                    stillWaiting = store.eventsOf(sleepId, onlyPending = true).toSet()
                }
                if (request is TranscriptionRequest.Waiting) {
                    if (request.pauseOnLowBattery && batteryLow()) return Outcome.LOW_BATTERY
                    if (store.isRecording()) return Outcome.RECORDING
                }
                if (request is TranscriptionRequest.Night) stopReason(request, sleepId)?.let { return it }
                if (rerun) pauses.rerunAt(sleepId, Rerun(event.start, events.size - index))
                // A single event the user asked for is recognised also while its sleep is paused.
                if (request !is TranscriptionRequest.Event && sleepId in pauses.paused()) {
                    // A run over waiting events goes on with the next sleep that is not paused.
                    if (request is TranscriptionRequest.Waiting) break
                    return Outcome.PAUSED
                }
                if (stillWaiting != null && event !in stillWaiting) {
                    report(TranscriptionProgress(sleepId, index + 1, events.size))
                    continue
                }
                val opened = analysis.get() ?: return Outcome.NO_MODEL
                if (!analyse(store, opened, sleepId, event)) {
                    if (request is TranscriptionRequest.Event) return Outcome.FAILED
                    failed += sleepId to event
                    if (request is TranscriptionRequest.Night) request.failures.merge(event, 1, Int::plus)
                }
                report(TranscriptionProgress(sleepId, index + 1, events.size))
            }
            if (request !is TranscriptionRequest.Waiting) {
                if (rerun) pauses.rerunAt(sleepId, null)
                return Outcome.FINISHED
            }
        }
    }

    /**
     * Lets the [search] search the sleeps of [request] it has not searched to their end, or the
     * sleep from its start again when asked. Returns the outcome when the run has to stop there,
     * else null.
     */
    private suspend fun searchNewWay(request: TranscriptionRequest): Outcome? {
        val search = search ?: return null
        val sleeps = when (request) {
            is TranscriptionRequest.Event, is TranscriptionRequest.Night -> return null
            is TranscriptionRequest.Sleep ->
                if (request.searchAgain || request.sleepId in search.sleepsToSearch()) listOf(request.sleepId) else return null
            is TranscriptionRequest.Waiting -> search.sleepsToSearch()
        }
        try {
            for (sleepId in sleeps) {
                when (val reason = stopReason(request, sleepId)) {
                    null -> Unit
                    // A run over waiting sleeps goes on with the next one that is not paused.
                    Outcome.PAUSED -> if (request is TranscriptionRequest.Waiting) continue else return reason
                    else -> return reason
                }
                val outcome = search.search(
                    sleepId,
                    again = request is TranscriptionRequest.Sleep && request.searchAgain,
                    onProgress = { share -> newWay.value = NewWayProgress(sleepId, searching = true, done = (share * PERCENT).toInt(), total = PERCENT) },
                    shouldStop = { giveWay(); stopReason(request, sleepId) != null },
                )
                when (outcome) {
                    // Without its models no sleep is searched.
                    SearchOutcome.NO_MODEL -> break
                    SearchOutcome.STOPPED -> when (val reason = stopReason(request, sleepId)) {
                        Outcome.PAUSED -> if (request is TranscriptionRequest.Waiting) continue else return reason
                        null -> continue
                        else -> return reason
                    }
                    SearchOutcome.DONE, SearchOutcome.FAILED -> Unit
                }
            }
        } finally {
            newWay.value = null
        }
        return null
    }

    /**
     * Why a run over [request] stops at [sleepId] now: a low battery, a recording or the user's
     * pause, or for a run during the night its conditions or the end of the recording, after which
     * the search after the night takes over; null to go on.
     */
    private suspend fun stopReason(request: TranscriptionRequest, sleepId: Long): Outcome? = when {
        request is TranscriptionRequest.Waiting && request.pauseOnLowBattery && batteryLow() -> Outcome.LOW_BATTERY
        request is TranscriptionRequest.Waiting && store.isRecording() -> Outcome.RECORDING
        request is TranscriptionRequest.Night && !store.isRecording() -> Outcome.FINISHED
        request is TranscriptionRequest.Night && !request.mayRun() -> Outcome.NOT_ALLOWED
        sleepId in pauses.paused() -> Outcome.PAUSED
        else -> null
    }

    /** The first sleep of [store] that is not paused, with its waiting events that did not fail in this run. */
    private suspend fun nextWaiting(store: TranscriptStore, failed: Set<Pair<Long, Stretch>>): Pair<Long, List<Stretch>>? {
        val paused = pauses.paused()
        for (sleepId in store.sleepsToTranscribe()) {
            if (sleepId in paused) continue
            val events = store.eventsOf(sleepId, onlyPending = true).filter { (sleepId to it) !in failed }
            if (events.isNotEmpty()) return sleepId to events
        }
        return null
    }

    /** Analyses one event of [store] and stores its result; false if the analysis failed. */
    private suspend fun analyse(store: TranscriptStore, analysis: EventAnalysis, sleepId: Long, event: Stretch): Boolean {
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

    /** The speech recognition of a run, loaded on first use; closed, it is loaded again when used next. */
    private class LoadedAnalysis(private val open: suspend () -> EventAnalysis?) {
        private var analysis: EventAnalysis? = null

        suspend fun get(): EventAnalysis? = analysis ?: open()?.also { analysis = it }

        fun close() {
            analysis?.close()
            analysis = null
        }
    }

    private fun batteryLow(): Boolean = battery().let { !it.charging && it.percent < LOW_BATTERY_PERCENT }

    companion object {
        /** Below this charge an automatic run pauses until the phone charges. */
        const val LOW_BATTERY_PERCENT = 30

        /** How often a run during the night tries a piece the analysis fails on. */
        const val NIGHT_ATTEMPTS = 3

        /** The total a search's progress counts up to. */
        private const val PERCENT = 100
    }
}
