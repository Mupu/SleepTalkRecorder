package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.TranscriptionCount
import me.mupu.sleeptalkrecorder.models.ModelLibrary
import me.mupu.sleeptalkrecorder.models.ModelState
import me.mupu.sleeptalkrecorder.models.ModelStatus
import me.mupu.sleeptalkrecorder.models.SoundModel
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import me.mupu.sleeptalkrecorder.playback.Stretch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Where speech recognition stands for one sleep, as the sleep screen shows it. */
sealed interface TranscriptionStatus {
    /** The sleep has no events, or they are not found yet. */
    data object NoEvents : TranscriptionStatus

    /** It runs on this sleep: [done] of the [total] events of this run. */
    data class Running(val done: Int, val total: Int) : TranscriptionStatus

    /** [pending] of the sleep's [events] wait, for the [reason]. */
    data class Waiting(val pending: Int, val events: Int, val reason: WaitReason) : TranscriptionStatus

    /**
     * The user paused it with [left] of the sleep's [events] still to do; it goes on with the next
     * run [mode] starts on its own, or when the user continues it.
     */
    data class Paused(val left: Int, val events: Int, val mode: TranscriptionMode) : TranscriptionStatus

    /** Every event has a result. */
    data object Done : TranscriptionStatus

    /** The user switched speech recognition off. */
    data object Off : TranscriptionStatus

    companion object {
        /**
         * The status of sleep [sleepId] from its [count], the run in [progress], the [mode] of the
         * settings, whether a model [hasModel] is installed, whether the last run after the
         * stop paused for the battery, and the user's [pauses].
         */
        fun of(
            sleepId: Long,
            count: TranscriptionCount,
            progress: TranscriptionProgress?,
            mode: TranscriptionMode,
            hasModel: Boolean,
            batteryPause: Boolean,
            pauses: PauseState,
        ): TranscriptionStatus {
            // A paused run over all events knows how many are left; else the waiting ones are.
            val pausedLeft = if (sleepId in pauses.paused) pauses.reruns[sleepId]?.left ?: count.pending else 0
            return when {
                // Switching it off cancels a run, which may report progress a moment longer.
                mode == TranscriptionMode.OFF -> Off
                // The event in progress finishes after the pause, which shows at once.
                pausedLeft > 0 -> Paused(pausedLeft, count.events, mode)
                else -> unpaused(sleepId, count, progress, mode, hasModel, batteryPause)
            }
        }

        private fun unpaused(
            sleepId: Long,
            count: TranscriptionCount,
            progress: TranscriptionProgress?,
            mode: TranscriptionMode,
            hasModel: Boolean,
            batteryPause: Boolean,
        ): TranscriptionStatus = when {
            progress?.sleepId == sleepId -> Running(progress.done, progress.total)
            count.events == 0 -> NoEvents
            count.pending == 0 -> Done
            else -> Waiting(
                count.pending,
                count.events,
                when {
                    !hasModel -> WaitReason.MODEL
                    mode == TranscriptionMode.MANUAL -> WaitReason.MANUAL
                    mode == TranscriptionMode.WHILE_CHARGING -> WaitReason.CHARGER
                    batteryPause && progress == null -> WaitReason.BATTERY
                    else -> WaitReason.QUEUE
                },
            )
        }
    }
}

/** Why events wait for speech recognition. */
enum class WaitReason {
    /** Another sleep is recognised first, or the run is about to start. */
    QUEUE,

    /** No model is installed. */
    MODEL,

    /** The settings let it run only when the user starts it. */
    MANUAL,

    /** The settings let it run only while the phone charges. */
    CHARGER,

    /** It paused for a low battery until the phone charges. */
    BATTERY,
}

/**
 * The active model is another than the one that made transcripts of a sleep, so a re-run with
 * [active] is offered: [previous] is the id of the model that made most of the others.
 */
data class ModelChange(val previous: String, val active: TranscriptionModel) {
    companion object {
        /**
         * The change for a sleep whose phone transcripts were made by the models in
         * [transcriptsByModel] (their ids with the number of events each made), with [active] the
         * active model; null when the active model made every transcript, or there is no model.
         * A re-run cut short leaves transcripts of both, and still offers the rest.
         */
        fun of(transcriptsByModel: Map<String, Int>, active: TranscriptionModel?): ModelChange? {
            if (active == null) return null
            val previous = transcriptsByModel.filterKeys { it != active.id }.maxByOrNull { it.value }?.key ?: return null
            return ModelChange(previous, active)
        }
    }
}

/** One event being recognised again with [model], or waiting for the run in progress to end. */
data class EventRecognition(val sleepId: Long, val event: Stretch, val model: TranscriptionModel)

/**
 * Speech recognition as the screens see it: the status of a sleep, starting it by hand for a
 * sleep or for one event, and pausing and continuing it. A pause holds until the next run the
 * settings start on their own: after the next recording ([TranscriptionMode.AFTER_STOP]), on the
 * next charge ([TranscriptionMode.WHILE_CHARGING]), or only when the user continues or starts a
 * run ([TranscriptionMode.MANUAL]). Then every paused sleep goes on where it stopped.
 */
class Transcriptions(
    private val runner: TranscriptionRunner,
    private val scheduler: TranscriptionScheduler,
    private val pauses: TranscriptionPauses,
    private val settings: SettingsStore,
    private val models: ModelLibrary,
    /** Where single events are recognised, so leaving the screen does not stop them. */
    private val scope: CoroutineScope,
    /** Whether a charger is plugged in now. */
    private val pluggedIn: () -> Boolean,
    /** Loads the speech recognition of a model; null if it is not installed or cannot be loaded. */
    private val open: suspend (TranscriptionModel) -> EventAnalysis?,
) {
    private val recognising = MutableStateFlow(emptyList<EventRecognition>())
    private val recognised = MutableSharedFlow<Pair<EventRecognition, TranscriptionRunner.Outcome>>(extraBufferCapacity = 8)

    /** The single events being recognised again, in the order they were asked for. */
    val eventRecognitions: StateFlow<List<EventRecognition>> = recognising.asStateFlow()

    /** Each single event once it was recognised again, with how that ended. */
    val eventRecognised: Flow<Pair<EventRecognition, TranscriptionRunner.Outcome>> = recognised.asSharedFlow()

    /** The installed models, in catalogue order. */
    val installedModels: Flow<List<ModelStatus>> = models.models.map { list -> list.filter { it.state == ModelState.Installed } }

    /** Whether the sound models the second way of finding events needs by default are installed. */
    val soundModelsReady: Flow<Boolean> = models.soundModels.map { list ->
        list.filter { it.model in SoundModel.RECOMMENDED }.all { it.state == ModelState.Installed }
    }

    /** How far the second way of finding events is with [sleepId]; null while it does not work on it. */
    fun newWayProgress(sleepId: Long): Flow<NewWayProgress?> = runner.newWayProgress.map { it?.takeIf { progress -> progress.sleepId == sleepId } }

    /** Where speech recognition stands for [sleepId], whose events are counted in [count]. */
    fun status(sleepId: Long, count: Flow<TranscriptionCount>): Flow<TranscriptionStatus> = combine(
        count,
        runner.progress,
        settings.flow(Settings.transcriptionMode),
        models.models.map { list -> list.any { it.active } },
        combine(scheduler.batteryPause, pauses.state, ::Pair),
    ) { count, progress, mode, hasModel, (batteryPause, paused) ->
        TranscriptionStatus.of(sleepId, count, progress, mode, hasModel, batteryPause, paused)
    }

    /**
     * Whether the active model is another than the one that made the transcripts of a sleep,
     * whose events per model that made the result they show are [transcriptsByModel].
     */
    fun modelChange(transcriptsByModel: Flow<Map<String, Int>>): Flow<ModelChange?> = combine(
        transcriptsByModel,
        models.models.map { list -> list.firstOrNull { it.active }?.model },
    ) { byModel, active -> ModelChange.of(byModel, active) }

    /** Recognises speech in every event of [sleepId] again, now; every paused sleep goes on as well. */
    suspend fun runNow(sleepId: Long) {
        resume()
        scheduler.run(TranscriptionRequest.Sleep(sleepId))
    }

    /**
     * Lets the second way of finding events search [sleepId] again from its start now, with the
     * current settings, once the first way's events wait for nothing, and recognises its events
     * that are new then; every paused sleep goes on as well.
     */
    suspend fun searchAgain(sleepId: Long) {
        resume()
        scheduler.run(TranscriptionRequest.Sleep(sleepId, onlyPending = true, searchAgain = true))
    }

    /** Pauses speech recognition of [sleepId] before its next event, until the next run the settings start. */
    suspend fun pause(sleepId: Long) {
        pauses.pause(sleepId, charging = pluggedIn())
        if (settings.flow(Settings.transcriptionMode).first() == TranscriptionMode.WHILE_CHARGING) scheduler.watchCharger()
    }

    /** Ends every pause: the paused sleeps go on where they stopped. */
    suspend fun resume() {
        val ended = pauses.clear()
        val mode = settings.flow(Settings.transcriptionMode).first()
        if (mode == TranscriptionMode.OFF) return
        for (sleepId in ended.paused) {
            val rerun = ended.reruns[sleepId]
            when {
                rerun != null -> scheduler.run(TranscriptionRequest.Sleep(sleepId, from = rerun.from))
                // Without a job of its own in this mode, the sleep's waiting events get one.
                mode == TranscriptionMode.MANUAL -> scheduler.run(TranscriptionRequest.Sleep(sleepId, onlyPending = true))
            }
        }
        follow(mode)
    }

    /** A recording ended: in [TranscriptionMode.AFTER_STOP] that ends every pause. */
    suspend fun recordingEnded() {
        if (settings.flow(Settings.transcriptionMode).first() == TranscriptionMode.AFTER_STOP) resume()
    }

    /** Makes sure waiting events are recognised as [mode] says, and that a pause ends on the next charge in its mode. */
    suspend fun follow(mode: TranscriptionMode) {
        scheduler.follow(mode)
        if (mode == TranscriptionMode.WHILE_CHARGING && pauses.paused().isNotEmpty()) scheduler.watchCharger()
    }

    /**
     * Recognises speech in [event] of [sleepId] again with [model], which keeps its own result
     * beside those of other models. It takes seconds, also while a run is in progress, which gives
     * way to it and goes on afterwards.
     */
    fun recogniseEvent(sleepId: Long, event: Stretch, model: TranscriptionModel) {
        val recognition = EventRecognition(sleepId, event, model)
        if (recognition in recognising.value) return
        recognising.update { it + recognition }
        scope.launch {
            try {
                val outcome = runner.run(TranscriptionRequest.Event(sleepId, event)) { open(model) }
                recognised.emit(recognition to outcome)
            } finally {
                recognising.update { it - recognition }
            }
        }
    }
}
