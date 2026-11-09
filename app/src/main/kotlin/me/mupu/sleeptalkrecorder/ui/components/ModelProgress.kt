package me.mupu.sleeptalkrecorder.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.models.DownloadFailure
import me.mupu.sleeptalkrecorder.models.ModelState

/** A size in bytes as the phone writes it: "671 MB". */
@Composable
fun fileSize(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/**
 * How a model's download stands, for the setup and the settings: a progress bar while it waits or
 * runs, the reason once it failed, nothing otherwise.
 */
@Composable
fun ModelProgress(state: ModelState, modifier: Modifier = Modifier) {
    when (state) {
        ModelState.Waiting -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            ProgressText(stringResource(R.string.model_waiting))
        }
        is ModelState.Downloading -> Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            LinearProgressIndicator(
                progress = { state.bytesDone.toFloat() / state.bytesTotal },
                modifier = Modifier.fillMaxWidth(),
            )
            ProgressText(
                stringResource(R.string.model_progress, fileSize(state.bytesDone), fileSize(state.bytesTotal)) +
                    " · " + stringResource(R.string.percent, (state.bytesDone * 100 / state.bytesTotal).toInt()),
            )
        }
        is ModelState.Failed -> Text(
            stringResource(
                when (state.failure) {
                    DownloadFailure.NETWORK -> R.string.model_failed_network
                    DownloadFailure.DAMAGED -> R.string.model_failed_damaged
                    DownloadFailure.NO_SPACE -> R.string.model_failed_space
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier,
        )
        ModelState.NotInstalled, ModelState.Installed -> Unit
    }
}

@Composable
private fun ProgressText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
