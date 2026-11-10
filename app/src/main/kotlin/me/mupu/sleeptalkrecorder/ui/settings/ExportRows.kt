package me.mupu.sleeptalkrecorder.ui.settings

import android.text.format.Formatter
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
 * The export for analysis: what it copies and where to, the size of the last export, and the
 * buttons to export again or to delete it.
 */
@Composable
internal fun ExportSetting(state: ExportState, path: String?, onExport: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    val status = when (state) {
        ExportState.Running -> stringResource(R.string.export_running)
        ExportState.Failed -> stringResource(R.string.export_failed)
        is ExportState.Idle -> state.bytes?.let { stringResource(R.string.export_done, Formatter.formatShortFileSize(context, it)) }
    }
    val hint = listOfNotNull(stringResource(R.string.export_hint, path ?: "–"), status).joinToString("\n")

    SettingLabel(icon = R.drawable.ic_download, label = stringResource(R.string.export), hint = hint)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = ControlPadding) {
        OutlinedButton(onClick = onExport, enabled = state != ExportState.Running && path != null) {
            Text(stringResource(R.string.export_action), maxLines = 1)
        }
        if (state == ExportState.Running) {
            CircularProgressIndicator(Modifier.size(24.dp).padding(start = 12.dp))
        }
        if (state is ExportState.Idle && state.bytes != null) {
            TextButton(onClick = onDelete) { Text(stringResource(R.string.delete), maxLines = 1) }
        }
    }
}
