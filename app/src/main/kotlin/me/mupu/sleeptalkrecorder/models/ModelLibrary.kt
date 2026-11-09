package me.mupu.sleeptalkrecorder.models

import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Where a model stands on this phone. */
sealed interface ModelState {
    /** Neither installed nor downloading. */
    data object NotInstalled : ModelState

    /** A download is queued, for example until the phone is online again. */
    data object Waiting : ModelState

    /** A download is running; [bytesDone] of the model's [bytesTotal] are on the phone. */
    data class Downloading(val bytesDone: Long, val bytesTotal: Long) : ModelState

    /** The last download ended with [failure]; nothing is downloading now. */
    data class Failed(val failure: DownloadFailure) : ModelState

    /** Every file is downloaded and checked. */
    data object Installed : ModelState
}

/** Why a model download gave up. */
enum class DownloadFailure {
    /** The connection failed again and again. */
    NETWORK,

    /** A downloaded file did not match its checksum and was thrown away. */
    DAMAGED,

    /** The phone has not enough free space. */
    NO_SPACE,
}

/** A model as the settings list it. */
data class ModelStatus(val model: TranscriptionModel, val state: ModelState, val active: Boolean)

/** The downloads that run in the background, by model; the app's are WorkManager jobs. */
interface ModelDownloads {
    /** Every model with a queued, running or failed download, as [ModelState.Waiting], [ModelState.Downloading] or [ModelState.Failed]. */
    val states: Flow<Map<TranscriptionModel, ModelState>>

    fun start(model: TranscriptionModel)

    fun cancel(model: TranscriptionModel)
}

/**
 * The transcription models: which are installed or downloading, which one is active, and the
 * actions that change that. The active model is a setting; it only ever names an installed model
 * unless none is installed, in which case transcripts wait.
 */
class ModelLibrary(
    private val installer: ModelInstaller,
    private val downloads: ModelDownloads,
    private val settings: SettingsStore,
) {
    // Raised after a delete, so the installed state is read again from disk.
    private val revision = MutableStateFlow(0)

    /** Every model with its state, in catalogue order. */
    val models: Flow<List<ModelStatus>> = combine(
        downloads.states,
        settings.flow(Settings.transcriptionModel),
        revision,
    ) { running, active, _ ->
        TranscriptionModel.entries.map { model ->
            val installed = isInstalled(model)
            val state = if (installed) ModelState.Installed else running[model] ?: ModelState.NotInstalled
            ModelStatus(model, state, active = installed && model == active)
        }
    }.flowOn(Dispatchers.IO)

    /** Downloads [model] in the background; a partial earlier download is continued. */
    fun install(model: TranscriptionModel) = downloads.start(model)

    /** Stops [model]'s download. */
    fun cancel(model: TranscriptionModel) = downloads.cancel(model)

    /** Makes [model] the one transcripts use, if it is installed. */
    suspend fun activate(model: TranscriptionModel) {
        if (isInstalled(model)) settings.set(Settings.transcriptionModel, model)
    }

    /** Deletes [model]'s files; if it was active, another installed model takes over. */
    suspend fun delete(model: TranscriptionModel) {
        downloads.cancel(model)
        withContext(Dispatchers.IO) { installer.delete(model.id) }
        revision.update { it + 1 }

        if (settings.flow(Settings.transcriptionModel).first() == model) {
            TranscriptionModel.entries.firstOrNull { isInstalled(it) }?.let { settings.set(Settings.transcriptionModel, it) }
        }
    }

    /** Called once [model] is installed: it becomes active unless an installed model already is. */
    suspend fun installed(model: TranscriptionModel) {
        if (!isInstalled(settings.flow(Settings.transcriptionModel).first())) {
            settings.set(Settings.transcriptionModel, model)
        }
    }

    private suspend fun isInstalled(model: TranscriptionModel) =
        withContext(Dispatchers.IO) { installer.isInstalled(model.id, model.files) }
}
