package me.mupu.sleeptalkrecorder.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.ui.components.UpdateSteps
import me.mupu.sleeptalkrecorder.ui.components.foundVersion
import me.mupu.sleeptalkrecorder.ui.components.updateStatus
import me.mupu.sleeptalkrecorder.update.UpdateState

/**
 * The update once one is found: its download on a tap (on mobile data), the download's progress,
 * the Update button, and why a step failed. Nothing while there is no update; a failed check is
 * only shown in the settings, since being offline is common. Home leaves it out during a recording.
 */
@Composable
internal fun UpdateCard(state: UpdateState, onDownload: () -> Unit, onInstall: () -> Unit) {
    if (state.foundVersion == null) return
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
                Text(updateStatus(state), style = MaterialTheme.typography.labelLarge)
                UpdateSteps(state, onDownload, onInstall)
            }
        }
    }
}
