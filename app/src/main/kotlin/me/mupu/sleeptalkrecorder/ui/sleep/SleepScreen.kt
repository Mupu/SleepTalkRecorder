package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import me.mupu.sleeptalkrecorder.ui.library.sleepSubtitle
import java.time.ZoneId

/** One sleep: so far its name, its times and its length. */
@Composable
fun SleepScreen(viewModel: SleepViewModel, onBack: () -> Unit) {
    val sleep by viewModel.sleep.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()

    val title = sleep?.let { sleepTitle(resources, SleepDate.of(it.startedAt, zone)) }.orEmpty()
    Scaffold(topBar = { AppBar(title, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            sleep?.let {
                Text(
                    sleepSubtitle(context, it, zone),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
