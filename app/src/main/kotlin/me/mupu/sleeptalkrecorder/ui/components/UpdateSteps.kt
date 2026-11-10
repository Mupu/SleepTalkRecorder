package me.mupu.sleeptalkrecorder.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.update.UpdateProblem
import me.mupu.sleeptalkrecorder.update.UpdateState
import me.mupu.sleeptalkrecorder.whatsnew.Version

/** What a tap on the update's button does: download it or install it. */
enum class UpdateAction { DOWNLOAD, INSTALL }

/** The update [state] is about, once one was found; null before, and when a check failed. */
val UpdateState.foundVersion: Version?
    get() = when (this) {
        is UpdateState.Available -> version
        is UpdateState.Downloading -> version
        is UpdateState.Ready -> version
        is UpdateState.Installing -> version
        is UpdateState.Failed -> version
        UpdateState.Off, UpdateState.Unchecked, UpdateState.Checking, UpdateState.UpToDate -> null
    }

/** The button a found update offers in [this] state; null when there is nothing to tap. */
val UpdateState.action: UpdateAction?
    get() = when (this) {
        is UpdateState.Available -> UpdateAction.DOWNLOAD
        is UpdateState.Ready -> UpdateAction.INSTALL
        is UpdateState.Failed -> when (problem) {
            UpdateProblem.DOWNLOAD_FAILED, UpdateProblem.DAMAGED -> UpdateAction.DOWNLOAD
            UpdateProblem.INSTALL_FAILED -> UpdateAction.INSTALL
            // Downloading the same file again would give the same answer.
            UpdateProblem.OTHER_SIGNER, UpdateProblem.NOT_AN_UPDATE, UpdateProblem.CHECK_FAILED -> null
        }.takeIf { version != null }
        UpdateState.Off, UpdateState.Unchecked, UpdateState.Checking, UpdateState.UpToDate,
        is UpdateState.Downloading, is UpdateState.Installing -> null
    }

/**
 * The steps of a found update below its status line, as home's update card and the settings show
 * them: the hint, the download's progress, the reason a step failed, and the button of [action].
 * Nothing while no update is found.
 */
@Composable
fun UpdateSteps(state: UpdateState, onDownload: () -> Unit, onInstall: () -> Unit, modifier: Modifier = Modifier) {
    if (state.foundVersion == null) return
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state) {
            is UpdateState.Available -> Hint(stringResource(R.string.update_download_metered))
            is UpdateState.Downloading -> {
                LinearProgressIndicator(
                    progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(R.string.model_progress, fileSize(state.done), fileSize(state.total)))
            }
            is UpdateState.Ready -> Hint(stringResource(R.string.update_ready_hint))
            is UpdateState.Installing -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is UpdateState.Failed -> Hint(stringResource(state.problem.reason))
            UpdateState.Off, UpdateState.Unchecked, UpdateState.Checking, UpdateState.UpToDate -> Unit
        }
        when (state.action) {
            UpdateAction.DOWNLOAD -> Button(onClick = onDownload) {
                Text(
                    if (state is UpdateState.Available) {
                        stringResource(R.string.update_download, fileSize(state.bytes))
                    } else {
                        stringResource(R.string.model_try_again)
                    },
                )
            }
            UpdateAction.INSTALL -> Button(onClick = onInstall) {
                Text(stringResource(if (state is UpdateState.Ready) R.string.update_install else R.string.model_try_again))
            }
            null -> Unit
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall)
}
