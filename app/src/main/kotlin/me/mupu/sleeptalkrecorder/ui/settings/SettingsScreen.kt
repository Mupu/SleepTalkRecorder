package me.mupu.sleeptalkrecorder.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.settings.AppLanguage
import me.mupu.sleeptalkrecorder.settings.AppLanguages
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.ThemeMode
import me.mupu.sleeptalkrecorder.settings.TimeFormat
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon

/** The settings screen: appearance (theme, language, time format), recording and cut playback. */
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val language = AppLanguages.shown(LocalConfiguration.current)

    Scaffold(topBar = { AppBar(stringResource(R.string.settings), navigationIcon = { BackIcon(onBack) }) }) { padding ->
        val settings = state ?: return@Scaffold
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SettingsSection(stringResource(R.string.settings_appearance))
            ChoiceSetting(
                icon = R.drawable.ic_contrast,
                label = stringResource(R.string.settings_theme),
                options = ThemeMode.entries,
                selected = settings.theme,
                optionLabel = { stringResource(it.label) },
                onSelect = { viewModel.set(Settings.theme, it) },
            )
            ChoiceSetting(
                icon = R.drawable.ic_translate,
                label = stringResource(R.string.settings_language),
                options = AppLanguage.entries,
                selected = language,
                optionLabel = { it.nativeName },
                onSelect = viewModel::setLanguage,
            )
            ChoiceSetting(
                icon = R.drawable.ic_schedule,
                label = stringResource(R.string.settings_time_format),
                options = TimeFormat.entries,
                selected = settings.timeFormat,
                optionLabel = { stringResource(it.label) },
                onSelect = { viewModel.set(Settings.timeFormat, it) },
            )

            SettingsSection(stringResource(R.string.settings_recording))
            SwitchableTimeSetting(
                icon = R.drawable.ic_alarm_off,
                label = stringResource(R.string.settings_stop_at),
                hint = stringResource(R.string.settings_stop_at_hint),
                enabled = settings.stopAtEnabled,
                time = settings.stopAt,
                onEnabledChange = { viewModel.set(Settings.stopAtEnabled, it) },
                onTimeChange = { viewModel.set(Settings.stopAt, it) },
            )
            SwitchSetting(
                icon = R.drawable.ic_auto_delete,
                label = stringResource(R.string.settings_auto_delete),
                hint = stringResource(
                    if (settings.autoDelete) R.string.settings_auto_delete_on else R.string.settings_auto_delete_off,
                ),
                checked = settings.autoDelete,
                onCheckedChange = { viewModel.set(Settings.autoDelete, it) },
            ) {
                if (settings.autoDelete) {
                    val days = settings.autoDeleteAfter.toDays().toInt()
                    DaysField(
                        value = settings.autoDeleteAfter,
                        range = Settings.autoDeleteAfter.range,
                        valueLabel = pluralStringResource(R.plurals.settings_auto_delete_after_days, days, days),
                        dialogTitle = stringResource(R.string.settings_auto_delete_dialog_title),
                        onChange = { viewModel.set(Settings.autoDeleteAfter, it) },
                    )
                }
            }

            SettingsSection(stringResource(R.string.settings_playback))
            DurationSetting(
                icon = R.drawable.ic_first_page,
                label = stringResource(R.string.settings_lead_in),
                hint = stringResource(R.string.settings_lead_in_hint),
                value = settings.leadIn,
                range = Settings.leadIn.range,
                onChange = { viewModel.set(Settings.leadIn, it) },
            )
            DurationSetting(
                icon = R.drawable.ic_last_page,
                label = stringResource(R.string.settings_tail),
                hint = stringResource(R.string.settings_tail_hint),
                value = settings.tail,
                range = Settings.tail.range,
                onChange = { viewModel.set(Settings.tail, it) },
            )
        }
    }
}

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

private val TimeFormat.label: Int
    get() = when (this) {
        TimeFormat.HOURS_24 -> R.string.time_format_24
        TimeFormat.HOURS_12 -> R.string.time_format_12
    }
