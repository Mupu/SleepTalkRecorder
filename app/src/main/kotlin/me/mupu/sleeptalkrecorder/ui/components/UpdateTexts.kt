package me.mupu.sleeptalkrecorder.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.update.UpdateProblem
import me.mupu.sleeptalkrecorder.update.UpdateState

/** What went wrong with an update, in the user's words. */
val UpdateProblem.reason: Int
    get() = when (this) {
        UpdateProblem.CHECK_FAILED -> R.string.update_status_check_failed
        UpdateProblem.DOWNLOAD_FAILED -> R.string.model_failed_network
        UpdateProblem.DAMAGED -> R.string.update_failed_damaged
        UpdateProblem.OTHER_SIGNER -> R.string.update_failed_signer
        UpdateProblem.NOT_AN_UPDATE -> R.string.update_failed_not_update
        UpdateProblem.INSTALL_FAILED -> R.string.update_failed_install
    }

/** Where the update stands, in one line, as the settings show it. */
@Composable
fun updateStatus(state: UpdateState): String = when (state) {
    UpdateState.Off -> stringResource(R.string.update_status_off)
    UpdateState.Unchecked -> stringResource(R.string.update_status_unchecked)
    UpdateState.Checking -> stringResource(R.string.update_status_checking)
    UpdateState.UpToDate -> stringResource(R.string.update_status_up_to_date)
    is UpdateState.Available -> stringResource(R.string.update_status_available, state.version.toString())
    is UpdateState.Downloading -> stringResource(R.string.update_status_downloading, state.version.toString())
    is UpdateState.Ready -> stringResource(R.string.update_status_ready, state.version.toString())
    is UpdateState.Installing -> stringResource(R.string.update_status_installing, state.version.toString())
    is UpdateState.Failed -> if (state.version == null) {
        stringResource(state.problem.reason)
    } else {
        stringResource(R.string.update_status_failed, state.version.toString())
    }
}
