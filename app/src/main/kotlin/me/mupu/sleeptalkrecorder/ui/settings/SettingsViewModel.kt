package me.mupu.sleeptalkrecorder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    val autoDelete: Boolean,
    val autoDeleteAfter: Duration,
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

/** State and actions of the settings screen. */
class SettingsViewModel(private val store: SettingsStore, private val models: ModelLibrary) : ViewModel() {

    private val deleting = MutableStateFlow<TranscriptionModel?>(null)

    /** Null until the stored settings are read, so the screen never shows defaults first. */
    val state: StateFlow<SettingsUiState?> = combine(
        // combine() takes at most five typed flows, so values shown together come in pairs.
        combine(store.flow(Settings.theme), store.flow(Settings.timeFormat), ::Pair),
        combine(store.flow(Settings.leadIn), store.flow(Settings.tail), ::Pair),
        combine(store.flow(Settings.stopAtEnabled), store.flow(Settings.stopAt), ::Pair),
        combine(store.flow(Settings.autoDelete), store.flow(Settings.autoDeleteAfter), ::Pair),
        combine(models.models, store.flow(Settings.transcriptLanguage), store.flow(Settings.transcriptionMode), deleting, ::Speech),
    ) { (theme, timeFormat), (leadIn, tail), (stopAtEnabled, stopAt), (autoDelete, autoDeleteAfter), speech ->
        SettingsUiState(
            theme, timeFormat, leadIn, tail, stopAtEnabled, stopAt, autoDelete, autoDeleteAfter,
            speech.models, speech.language, speech.mode, speech.deleting,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

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
