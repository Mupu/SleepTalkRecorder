package me.mupu.sleeptalkrecorder.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon

/**
 * The settings screen: appearance (theme, language, time format), recording, cut playback and the
 * speech recognition models.
 */
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

            SettingsSection(stringResource(R.string.settings_transcription))
            Text(
                stringResource(R.string.settings_models_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            )
            ChoiceSetting(
                icon = R.drawable.ic_record_voice,
                label = stringResource(R.string.settings_transcription_mode),
                options = TranscriptionMode.entries,
                selected = settings.transcriptionMode,
                optionLabel = { stringResource(it.label) },
                onSelect = { viewModel.set(Settings.transcriptionMode, it) },
                hint = stringResource(settings.transcriptionMode.hint),
            )
            if (settings.needsLanguage) {
                TranscriptLanguageSetting(settings.transcriptLanguage) { viewModel.set(Settings.transcriptLanguage, it) }
            }
            settings.models.forEach { status ->
                ModelRow(
                    status = status,
                    onInstall = { viewModel.install(status.model) },
                    onCancel = { viewModel.cancelDownload(status.model) },
                    onDelete = { viewModel.askDelete(status.model) },
                    onActivate = { viewModel.activate(status.model) },
                )
            }
        }

        settings.deleting?.let { model ->
            DeleteModelDialog(model, onConfirm = viewModel::confirmDelete, onDismiss = viewModel::cancelDelete)
        }
    }
}

private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.SYSTEM -> R.string.theme_system
        ThemeMode.LIGHT -> R.string.theme_light
        ThemeMode.DARK -> R.string.theme_dark
    }

private val TranscriptionMode.label: Int
    get() = when (this) {
        TranscriptionMode.AFTER_STOP -> R.string.transcription_mode_after_stop
        TranscriptionMode.WHILE_CHARGING -> R.string.transcription_mode_while_charging
        TranscriptionMode.MANUAL -> R.string.transcription_mode_manual
        TranscriptionMode.OFF -> R.string.transcription_mode_off
    }

private val TranscriptionMode.hint: Int
    get() = when (this) {
        TranscriptionMode.AFTER_STOP -> R.string.transcription_mode_after_stop_hint
        TranscriptionMode.WHILE_CHARGING -> R.string.transcription_mode_while_charging_hint
        TranscriptionMode.MANUAL -> R.string.transcription_mode_manual_hint
        TranscriptionMode.OFF -> R.string.transcription_mode_off_hint
    }

private val TimeFormat.label: Int
    get() = when (this) {
        TimeFormat.HOURS_24 -> R.string.time_format_24
        TimeFormat.HOURS_12 -> R.string.time_format_12
    }
