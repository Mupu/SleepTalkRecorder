package me.mupu.sleeptalkrecorder.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.settings.AppLanguage
import me.mupu.sleeptalkrecorder.settings.AppLanguages
import me.mupu.sleeptalkrecorder.settings.Setting
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.settings.ThemeMode
import me.mupu.sleeptalkrecorder.settings.TimeFormat
import java.time.Duration
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
)

/** State and actions of the settings screen. */
class SettingsViewModel(private val store: SettingsStore) : ViewModel() {

    /** Null until the stored settings are read, so the screen never shows defaults first. */
    val state: StateFlow<SettingsUiState?> = combine(
        store.flow(Settings.theme),
        store.flow(Settings.timeFormat),
        store.flow(Settings.leadIn),
        store.flow(Settings.tail),
        ::SettingsUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Stores [value] for [setting]; the state follows once it is written. */
    fun <T> set(setting: Setting<T>, value: T) {
        viewModelScope.launch { store.set(setting, value) }
    }

    fun setLanguage(language: AppLanguage) = AppLanguages.apply(language)
}
