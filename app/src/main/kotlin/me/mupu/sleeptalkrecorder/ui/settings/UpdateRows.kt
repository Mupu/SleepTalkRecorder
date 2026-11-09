package me.mupu.sleeptalkrecorder.ui.settings

import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.ui.components.updateStatus
import me.mupu.sleeptalkrecorder.update.UpdateState

/**
 * The rows of the updates section about updating: whether the app checks on every start, and
 * where the update stands with "Check now". Home shows a found update and its Update button.
 */
@Composable
internal fun UpdateSettings(state: UpdatesUiState, onCheckAutomaticallyChange: (Boolean) -> Unit, onCheckNow: () -> Unit) {
    if (state.update == UpdateState.Off) {
        SettingLabel(icon = R.drawable.ic_download, label = updateStatus(state.update))
        return
    }
    SwitchSetting(
        icon = R.drawable.ic_download,
        label = stringResource(R.string.settings_check_updates),
        hint = stringResource(R.string.settings_check_updates_hint),
        checked = state.checkAutomatically,
        onCheckedChange = onCheckAutomaticallyChange,
    )
    SettingLabel(icon = R.drawable.ic_phone_android, label = updateStatus(state.update)) {
        OutlinedButton(onClick = onCheckNow, enabled = state.canCheck) {
            Text(stringResource(R.string.settings_check_now))
        }
    }
}
