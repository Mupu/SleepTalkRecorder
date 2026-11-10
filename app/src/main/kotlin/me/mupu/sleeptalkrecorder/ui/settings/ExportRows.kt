package me.mupu.sleeptalkrecorder.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.ExportState

/**
 * The export for analysis: what it copies and where to, how much the recordings take and how much
 * a new export would still copy, the size of the last export, and the buttons to export or to
 * delete it.
 */
@Composable
internal fun ExportSetting(state: ExportState, path: String?, onExport: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val status = when (state) {
        is ExportState.Running -> listOf(stringResource(R.string.export_running, size(state.copied), size(state.total)))
        ExportState.Failed -> listOf(stringResource(R.string.export_failed))
        is ExportState.Idle -> listOfNotNull(
            if (state.recordings != null && state.toCopy != null) {
                stringResource(R.string.export_sizes, size(state.recordings), size(state.toCopy))
            } else {
                null
            },
            state.exported?.let { stringResource(R.string.export_done, size(it)) },
        )
    }
    val hint = (listOf(stringResource(R.string.export_hint, path ?: "–")) + status).joinToString("\n")

    SettingLabel(icon = R.drawable.ic_download, label = stringResource(R.string.export), hint = hint)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = ControlPadding) {
        OutlinedButton(onClick = onExport, enabled = state !is ExportState.Running && path != null) {
            Text(stringResource(R.string.export_action), maxLines = 1)
        }
        if (state is ExportState.Running) {
            // Indeterminate until the files to copy are counted, then the share of bytes copied.
            val progress = Modifier.weight(1f).padding(start = 16.dp)
            if (state.total > 0) {
                LinearProgressIndicator(progress = { state.copied.toFloat() / state.total }, modifier = progress)
            } else {
                LinearProgressIndicator(modifier = progress)
            }
        }
        if (state is ExportState.Idle && state.exported != null) {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), maxLines = 1) }
        }
    }
}
