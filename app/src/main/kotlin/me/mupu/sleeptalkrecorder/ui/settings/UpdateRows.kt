package me.mupu.sleeptalkrecorder.ui.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.ui.components.UpdateSteps
import me.mupu.sleeptalkrecorder.ui.components.updateStatus
import me.mupu.sleeptalkrecorder.update.UpdateState

/**
 * The rows of the updates section about updating: whether the app checks on every start, and
 * where the update stands. A found update shows the same steps and button as home's update card
 * below its status; otherwise the status has "Check now".
 */
@Composable
internal fun UpdateSettings(
    state: UpdatesUiState,
    onCheckAutomaticallyChange: (Boolean) -> Unit,
    onCheckNow: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
) {
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
    val checkNow: (@Composable () -> Unit)? = if (state.offersCheck) {
        {
            OutlinedButton(onClick = onCheckNow, enabled = state.canCheck) {
                Text(stringResource(R.string.settings_check_now))
            }
        }
    } else {
        null
    }
    SettingLabel(icon = R.drawable.ic_phone_android, label = updateStatus(state.update), trailing = checkNow)
    // Aligned with the status text, past the row's icon.
    UpdateSteps(state.update, onDownload, onInstall, Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp))
}
