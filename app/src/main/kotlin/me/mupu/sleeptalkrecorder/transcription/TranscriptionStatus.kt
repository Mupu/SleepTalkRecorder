package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.TranscriptionCount
import me.mupu.sleeptalkrecorder.models.ModelLibrary
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/** Where speech recognition stands for one sleep, as the sleep screen shows it. */
sealed interface TranscriptionStatus {
    /** The sleep has no events, or they are not found yet. */
    data object NoEvents : TranscriptionStatus

    /** It runs on this sleep: [done] of the [total] events of this run. */
    data class Running(val done: Int, val total: Int) : TranscriptionStatus

    /** [pending] of the sleep's [events] wait, for the [reason]. */
    data class Waiting(val pending: Int, val events: Int, val reason: WaitReason) : TranscriptionStatus

    /** Every event has a result. */
    data object Done : TranscriptionStatus

    companion object {
        /**
         * The status of sleep [sleepId] from its [count], the run in [progress], the [mode] of the
         * settings, whether a model [hasModel] is installed and whether the last run after the
         * stop paused for the battery.
         */
        fun of(
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

/** Speech recognition as the screens see it: the status of a sleep, and starting it by hand. */
class Transcriptions(
    private val runner: TranscriptionRunner,
    private val scheduler: TranscriptionScheduler,
    private val settings: SettingsStore,
    private val models: ModelLibrary,
) {
    /** Where speech recognition stands for [sleepId], whose events are counted in [count]. */
    fun status(sleepId: Long, count: Flow<TranscriptionCount>): Flow<TranscriptionStatus> = combine(
        count,
        runner.progress,
        settings.flow(Settings.transcriptionMode),
        models.models.map { list -> list.any { it.active } },
        scheduler.batteryPause,
    ) { count, progress, mode, hasModel, batteryPause ->
        TranscriptionStatus.of(sleepId, count, progress, mode, hasModel, batteryPause)
    }

    /**
     * Whether the active model is another than the one that made the transcripts of a sleep,
     * whose phone transcripts per model are [transcriptsByModel].
     */
    fun modelChange(transcriptsByModel: Flow<Map<String, Int>>): Flow<ModelChange?> = combine(
        transcriptsByModel,
        models.models.map { list -> list.firstOrNull { it.active }?.model },
    ) { byModel, active -> ModelChange.of(byModel, active) }

    /** Recognises speech in every event of [sleepId] again, now. */
    fun runNow(sleepId: Long) = scheduler.runNow(sleepId)
}
