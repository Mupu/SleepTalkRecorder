package me.mupu.sleeptalkrecorder.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.ui.components.fileSize
import me.mupu.sleeptalkrecorder.ui.components.reason
import me.mupu.sleeptalkrecorder.update.UpdateProblem
import me.mupu.sleeptalkrecorder.update.UpdateState

/**
 * The update once one is found: its download on a tap (on mobile data), the download's progress,
 * the Update button, and why a step failed. Nothing while there is no update; a failed check is
 * only shown in the settings, since being offline is common. Home leaves it out during a recording.
 */
@Composable
internal fun UpdateCard(state: UpdateState, onDownload: () -> Unit, onInstall: () -> Unit) {
    when (state) {
        is UpdateState.Available -> UpdateSurface(stringResource(R.string.update_status_available, state.version.toString())) {
            Text(stringResource(R.string.update_download_metered), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onDownload) { Text(stringResource(R.string.update_download, fileSize(state.bytes))) }
        }
        is UpdateState.Downloading -> UpdateSurface(stringResource(R.string.update_status_downloading, state.version.toString())) {
            LinearProgressIndicator(
                progress = { if (state.total > 0) state.done.toFloat() / state.total else 0f },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.model_progress, fileSize(state.done), fileSize(state.total)),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        is UpdateState.Ready -> UpdateSurface(stringResource(R.string.update_status_ready, state.version.toString())) {
            Text(stringResource(R.string.update_ready_hint), style = MaterialTheme.typography.bodySmall)
            Button(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
        }
        is UpdateState.Installing -> UpdateSurface(stringResource(R.string.update_status_installing, state.version.toString())) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        is UpdateState.Failed -> {
            val version = state.version ?: return
            val retry = when (state.problem) {
                UpdateProblem.DOWNLOAD_FAILED, UpdateProblem.DAMAGED -> onDownload
                UpdateProblem.INSTALL_FAILED -> onInstall
                // Downloading the same file again would give the same answer.
                UpdateProblem.OTHER_SIGNER, UpdateProblem.NOT_AN_UPDATE, UpdateProblem.CHECK_FAILED -> null
            }
            UpdateSurface(stringResource(R.string.update_status_failed, version.toString())) {
                Text(stringResource(state.problem.reason), style = MaterialTheme.typography.bodySmall)
                if (retry != null) {
                    Button(onClick = retry) {
                        Text(stringResource(R.string.model_try_again))
                    }
                }
            }
        }
        UpdateState.Off, UpdateState.Unchecked, UpdateState.Checking, UpdateState.UpToDate -> Unit
    }
}

@Composable
private fun UpdateSurface(title: String, content: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.secondaryContainer,
        contentColor = colors.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(R.drawable.ic_download), contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge)
                content()
            }
        }
    }
}
