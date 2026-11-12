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

/** A sound model as the settings list it. */
data class SoundModelStatus(val model: SoundModel, val state: ModelState)

/** The downloads that run in the background, by model; the app's are WorkManager jobs. */
interface ModelDownloads {
    /** Every model with a queued, running or failed download, as [ModelState.Waiting], [ModelState.Downloading] or [ModelState.Failed]. */
    val states: Flow<Map<DownloadableModel, ModelState>>

    /** Starts downloading [model], unless its download is already queued or running. */
    fun start(model: DownloadableModel)

    /** Stops the download of [model] and throws away what it downloaded so far. */
    fun cancel(model: DownloadableModel)
}

/**
 * The transcription models and the sound models: which are installed or downloading, which
 * transcription model is active, and the actions that change that. The active model is a setting;
 * it only ever names an installed model unless none is installed, in which case transcripts wait.
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

    /** Every sound model with its state, in catalogue order. */
    val soundModels: Flow<List<SoundModelStatus>> = combine(downloads.states, revision) { running, _ ->
        SoundModel.entries.map { model -> SoundModelStatus(model, if (isInstalled(model)) ModelState.Installed else running[model] ?: ModelState.NotInstalled) }
    }.flowOn(Dispatchers.IO)

    /** Downloads [model] in the background; a partial earlier download is continued. */
    fun install(model: DownloadableModel) = downloads.start(model)

    /** Stops [model]'s download. */
    fun cancel(model: DownloadableModel) = downloads.cancel(model)

    /** Deletes the sound model [model]'s files. */
    suspend fun delete(model: SoundModel) {
        downloads.cancel(model)
        withContext(Dispatchers.IO) { installer.delete(model.id) }
        revision.update { it + 1 }
    }

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

    /** Whether every file of [model] is downloaded and checked. */
    suspend fun isInstalled(model: DownloadableModel) =
        withContext(Dispatchers.IO) { installer.isInstalled(model.id, model.files) }
}
