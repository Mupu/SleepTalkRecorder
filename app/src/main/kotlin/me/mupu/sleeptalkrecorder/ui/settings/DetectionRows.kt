package me.mupu.sleeptalkrecorder.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.models.ModelState
import me.mupu.sleeptalkrecorder.models.SoundModel
import me.mupu.sleeptalkrecorder.models.SoundModelStatus
import me.mupu.sleeptalkrecorder.settings.FloatSetting
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import me.mupu.sleeptalkrecorder.ui.components.ModelProgress
import me.mupu.sleeptalkrecorder.ui.components.fileSize
import kotlin.math.roundToInt

/**
 * The sound models of the second way of finding events, each downloaded, cancelled or deleted on
 * its own, and whether it searches during the night.
 */
@Composable
internal fun SoundModelSettings(state: DetectionUiState, viewModel: SettingsViewModel) {
    SettingsSection(stringResource(R.string.settings_sound_models))
    Text(
        stringResource(R.string.settings_sound_models_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
    )
    for (status in state.soundModels) {
        SoundModelRow(
            status,
            onInstall = { viewModel.install(status.model) },
            onCancel = { viewModel.cancelDownload(status.model) },
            onDelete = { viewModel.askDelete(status.model) },
        )
    }
    SwitchSetting(
        icon = R.drawable.ic_bedtime,
        label = stringResource(R.string.settings_night_scan),
        hint = stringResource(
            when (state.transcriptionMode) {
                TranscriptionMode.AFTER_STOP -> R.string.settings_night_scan_after_stop
                TranscriptionMode.WHILE_CHARGING -> R.string.settings_night_scan_while_charging
                TranscriptionMode.MANUAL, TranscriptionMode.OFF -> R.string.settings_night_scan_never
            },
        ),
        checked = state.nightScan,
        onCheckedChange = { viewModel.set(Settings.nightScan, it) },
    )
    state.deleting?.let { model ->
        DeleteModelDialog(model.displayName, model.bytes, onConfirm = viewModel::confirmDeleteSound, onDismiss = viewModel::cancelDeleteSound)
    }
}

/** One sound model: what it is for and its size, with a button that downloads, cancels or deletes it. */
@Composable
private fun SoundModelRow(status: SoundModelStatus, onInstall: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit) {
    Column {
        ListItem(
            headlineContent = { Text(status.model.displayName) },
            supportingContent = { Text(stringResource(status.model.purpose) + " · " + fileSize(status.model.bytes)) },
            leadingContent = {
                Icon(
                    painterResource(if (status.state == ModelState.Installed) R.drawable.ic_check_circle else R.drawable.ic_download),
                    contentDescription = null,
                )
            },
            trailingContent = {
                when (status.state) {
                    ModelState.NotInstalled -> OutlinedButton(onClick = onInstall) { Text(stringResource(R.string.model_download)) }
                    is ModelState.Failed -> OutlinedButton(onClick = onInstall) { Text(stringResource(R.string.model_try_again)) }
                    ModelState.Waiting, is ModelState.Downloading -> IconButton(onClick = onCancel) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.model_cancel_download))
                    }
                    ModelState.Installed -> IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete))
                    }
                }
            },
        )
        ModelProgress(status.state, Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp))
    }
}

/** What a sound model is for, in one line. */
internal val SoundModel.purpose: Int
    get() = when (this) {
        SoundModel.CED_TINY -> R.string.sound_model_purpose_tiny
        SoundModel.CED_MINI -> R.string.sound_model_purpose_mini
        SoundModel.CED_SMALL -> R.string.sound_model_purpose_small
    }

/**
 * The developer mode switch and, while it is on, the settings of the second way of finding events
 * ("Detection (test)"), which apply to the sleeps it searches afterwards.
 */
@Composable
internal fun DeveloperSettings(state: DetectionUiState, viewModel: SettingsViewModel) {
    SettingsSection(stringResource(R.string.settings_developer))
    SwitchSetting(
        icon = R.drawable.ic_tune,
        label = stringResource(R.string.settings_developer_mode),
        hint = stringResource(R.string.settings_developer_mode_hint),
        checked = state.developerMode,
        onCheckedChange = { viewModel.set(Settings.developerMode, it) },
    )
    if (!state.developerMode) return

    SettingsSection(stringResource(R.string.settings_detection_test))
    Text(
        stringResource(R.string.settings_detection_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
    )
    NumberSetting(
        icon = R.drawable.ic_speed,
        label = stringResource(R.string.settings_search_sensitivity),
        hint = stringResource(R.string.settings_search_sensitivity_hint),
        value = state.sensitivity,
        setting = Settings.searchSensitivity,
        step = 0.05f,
        onChange = { viewModel.set(Settings.searchSensitivity, it) },
    )
    SwitchSetting(
        icon = R.drawable.ic_record_voice,
        label = stringResource(R.string.settings_listening),
        hint = stringResource(R.string.settings_listening_hint),
        checked = state.listening,
        onCheckedChange = { viewModel.set(Settings.searchListening, it) },
    ) {
        if (state.listening) {
            NumberSetting(
                icon = R.drawable.ic_tune,
                label = stringResource(R.string.settings_listening_threshold),
                hint = stringResource(R.string.settings_listening_threshold_hint),
                value = state.listeningThreshold,
                setting = Settings.listeningThreshold,
                step = 0.01f,
                onChange = { viewModel.set(Settings.listeningThreshold, it) },
            )
        }
    }
    NumberSetting(
        icon = R.drawable.ic_visibility_off,
        label = stringResource(R.string.settings_background_threshold),
        hint = stringResource(R.string.settings_background_threshold_hint),
        value = state.backgroundThreshold,
        setting = Settings.backgroundThreshold,
        step = 0.01f,
        onChange = { viewModel.set(Settings.backgroundThreshold, it) },
    )
    SwitchSetting(
        icon = R.drawable.ic_zoom_in,
        label = stringResource(R.string.settings_onset_vote),
        hint = stringResource(R.string.settings_onset_vote_hint),
        checked = state.onsetVote,
        onCheckedChange = { viewModel.set(Settings.onsetVote, it) },
    )
    ChoiceSetting(
        icon = R.drawable.ic_filter_list,
        label = stringResource(R.string.settings_sorting_model),
        options = SoundModel.SORTERS,
        selected = state.sortingModel,
        optionLabel = { it.displayName },
        onSelect = { viewModel.set(Settings.sortingModel, it) },
    )
}

/**
 * A number of [setting]'s range in steps of [step], set with a slider below its label and shown with
 * two decimals. It is stored once the slider is let go.
 */
@Composable
private fun NumberSetting(
    @DrawableRes icon: Int,
    label: String,
    hint: String,
    value: Float,
    setting: FloatSetting,
    step: Float,
    onChange: (Float) -> Unit,
) {
    var dragged by remember(value) { mutableFloatStateOf(value) }
    val range = setting.range
    val locale = LocalResources.current.configuration.locales[0]
    Column {
        SettingLabel(icon, label, hint) {
            Text("%.2f".format(locale, dragged), style = MaterialTheme.typography.labelLarge)
        }
        Slider(
            value = dragged,
            onValueChange = { dragged = (it / step).roundToInt() * step },
            onValueChangeFinished = { onChange(dragged) },
            valueRange = range,
            steps = ((range.endInclusive - range.start) / step).roundToInt() - 1,
            modifier = ControlPadding,
        )
    }
}
