package me.mupu.sleeptalkrecorder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.AnalysisExport
import me.mupu.sleeptalkrecorder.data.ExportState
import me.mupu.sleeptalkrecorder.models.ModelLibrary
import me.mupu.sleeptalkrecorder.models.ModelStatus
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.settings.AppLanguage
import me.mupu.sleeptalkrecorder.settings.AppLanguages
import me.mupu.sleeptalkrecorder.settings.Setting
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.settings.ThemeMode
import me.mupu.sleeptalkrecorder.settings.TimeFormat
import me.mupu.sleeptalkrecorder.settings.TranscriptLanguage
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import me.mupu.sleeptalkrecorder.time.SleepWindows
import me.mupu.sleeptalkrecorder.ui.components.action
import me.mupu.sleeptalkrecorder.update.UpdateState
import me.mupu.sleeptalkrecorder.update.Updater
import java.time.Duration
import java.time.LocalTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The stored settings the settings screen shows. The app language comes from the configuration. */
data class SettingsUiState(
    val theme: ThemeMode,
    val timeFormat: TimeFormat,
    val leadIn: Duration,
    val tail: Duration,
    val stopAtEnabled: Boolean,
    val stopAt: LocalTime,
    val sleepWindows: SleepWindows,
    val autoDelete: Boolean,
    val autoDeleteAfter: Duration,
    /** Whether automatic deletion keeps sleeps with tagged or named events. */
    val keepTagged: Boolean,
    /** Every transcription model with its state, in catalogue order. */
    val models: List<ModelStatus>,
    val transcriptLanguage: TranscriptLanguage,
    val transcriptionMode: TranscriptionMode,
    /** The model the user asked to delete, until they confirm or cancel. */
    val deleting: TranscriptionModel?,
) {
    /** Whether the active model needs the transcript language, which is only shown then. */
    val needsLanguage: Boolean get() = models.any { it.active && it.model.needsLanguage }
}

/** The updates section of the settings screen. */
data class UpdatesUiState(
    val checkAutomatically: Boolean,
    val update: UpdateState,
) {
    /**
     * Whether the row offers "Check now": not while a found update offers its own button, as home's
     * update card does, or downloads or installs.
     */
    val offersCheck: Boolean
        get() = update != UpdateState.Off && update.action == null &&
            update !is UpdateState.Downloading && update !is UpdateState.Installing

    /** Whether "Check now" can start a check: not while one runs. */
    val canCheck: Boolean
        get() = offersCheck && update != UpdateState.Checking
}

/** State and actions of the settings screen. */
class SettingsViewModel(
    private val store: SettingsStore,
    private val models: ModelLibrary,
    private val updater: Updater,
    private val export: AnalysisExport,
) : ViewModel() {

    /** Where the export for analysis stands. */
    val exportState: StateFlow<ExportState> = export.state

    /** The folder the export goes to, as shown in its hint; null without shared storage. */
    val exportPath: String? = export.path

    init {
        // The sizes change with every recording and deletion, so they are counted when the settings open.
        export.refresh()
    }

    /** Copies what changed since the last export for analysis. */
    fun exportForAnalysis() = export.export()

    /** Deletes the export for analysis. */
    fun deleteExport() = export.delete()

    private val deleting = MutableStateFlow<TranscriptionModel?>(null)

    /** Null until the stored settings are read, so the screen never shows defaults first. */
    val state: StateFlow<SettingsUiState?> = combine(
        // combine() takes at most five typed flows, so values shown together come in pairs or triples.
        combine(store.flow(Settings.theme), store.flow(Settings.timeFormat), ::Pair),
        combine(store.flow(Settings.leadIn), store.flow(Settings.tail), ::Pair),
        combine(store.flow(Settings.stopAtEnabled), store.flow(Settings.stopAt), store.flow(Settings.sleepWindows), ::Triple),
        combine(store.flow(Settings.autoDelete), store.flow(Settings.autoDeleteAfter), store.flow(Settings.keepTagged), ::Triple),
        combine(models.models, store.flow(Settings.transcriptLanguage), store.flow(Settings.transcriptionMode), deleting, ::Speech),
    ) { (theme, timeFormat), (leadIn, tail), (stopAtEnabled, stopAt, sleepWindows), (autoDelete, autoDeleteAfter, keepTagged), speech ->
        SettingsUiState(
            theme, timeFormat, leadIn, tail, stopAtEnabled, stopAt, sleepWindows, autoDelete, autoDeleteAfter, keepTagged,
            speech.models, speech.language, speech.mode, speech.deleting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The updates section; null until the setting is read. */
    val updates: StateFlow<UpdatesUiState?> = combine(store.flow(Settings.checkForUpdates), updater.state, ::UpdatesUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Looks for an update now. */
    fun checkForUpdates() = updater.check()

    fun downloadUpdate() = updater.download()

    fun installUpdate() = updater.install()

    /** The speech recognition settings, combined into one value like the pairs above. */
    private data class Speech(
        val models: List<ModelStatus>,
        val language: TranscriptLanguage,
        val mode: TranscriptionMode,
        val deleting: TranscriptionModel?,
    )

    /** Stores [value] for [setting]; the state follows once it is written. */
    fun <T> set(setting: Setting<T>, value: T) {
        viewModelScope.launch { store.set(setting, value) }
    }

    fun setLanguage(language: AppLanguage) = AppLanguages.apply(language)

    fun install(model: TranscriptionModel) = models.install(model)

    fun cancelDownload(model: TranscriptionModel) = models.cancel(model)

    fun activate(model: TranscriptionModel) {
        viewModelScope.launch { models.activate(model) }
    }

    /** Asks to confirm deleting [model]. */
    fun askDelete(model: TranscriptionModel) {
        deleting.value = model
    }

    fun confirmDelete() {
        val model = deleting.value ?: return
        deleting.value = null
        viewModelScope.launch { models.delete(model) }
    }

    fun cancelDelete() {
        deleting.value = null
    }
}
