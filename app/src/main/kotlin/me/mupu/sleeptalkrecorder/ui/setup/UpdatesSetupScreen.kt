package me.mupu.sleeptalkrecorder.ui.setup

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.readiness.SystemPage
import me.mupu.sleeptalkrecorder.readiness.openFirst
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import kotlinx.coroutines.launch

/** Actions of the setup page about installing updates. */
class UpdatesSetupViewModel(private val store: SettingsStore) : ViewModel() {

    fun finishSetupStep() {
        viewModelScope.launch { SetupStep.UPDATES.finish(store) }
    }

    fun backSetupStep() {
        viewModelScope.launch { SetupStep.UPDATES.back(store) }
    }
}

/**
 * Asks for "Install unknown apps" for this app, with which Android installs its updates without a
 * dialog. The access is optional: without it every update asks once.
 */
@Composable
fun UpdatesSetupScreen(viewModel: UpdatesSetupViewModel) {
    val activity = LocalActivity.current ?: return
    // The access is granted on a settings page, so it is read again whenever the app returns.
    var allowed by remember { mutableStateOf(activity.packageManager.canRequestPackageInstalls()) }
    LifecycleResumeEffect(Unit) {
        allowed = activity.packageManager.canRequestPackageInstalls()
        onPauseOrDispose {}
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepLabel(SetupStep.UPDATES)
            Text(stringResource(R.string.setup_updates_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.setup_updates_text), color = MaterialTheme.colorScheme.onSurfaceVariant)

            SetupRow(
                icon = if (allowed) R.drawable.ic_check_circle else R.drawable.ic_download,
                done = allowed,
                title = stringResource(R.string.setup_updates_install),
                required = false,
                text = stringResource(R.string.setup_updates_install_why, stringResource(R.string.app_name)),
            ) {
                if (allowed) {
                    Text(
                        stringResource(R.string.granted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FilledTonalButton(
                        onClick = { activity.openFirst(listOf(SystemPage.UNKNOWN_APP_SOURCES, SystemPage.APP_DETAILS)) },
                    ) {
                        Text(stringResource(R.string.allow))
                    }
                }
            }

            Text(
                stringResource(R.string.setup_updates_never_recording),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = viewModel::backSetupStep) { Text(stringResource(R.string.back)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = viewModel::finishSetupStep) { Text(stringResource(R.string.next)) }
            }
        }
    }
}
