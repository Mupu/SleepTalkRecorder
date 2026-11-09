package me.mupu.sleeptalkrecorder.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.models.ModelLibrary
import me.mupu.sleeptalkrecorder.models.ModelState
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.ui.components.ModelProgress
import me.mupu.sleeptalkrecorder.ui.components.fileSize
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** State and actions of the setup page that offers the default speech recognition model. */
class ModelSetupViewModel(
    private val store: SettingsStore,
    private val models: ModelLibrary,
    /** The steps of this build's setup, which decide the step before this one. */
    private val steps: List<SetupStep>,
) : ViewModel() {

    /** The default model's state; null until it is read. */
    val state: StateFlow<ModelState?> = models.models
        .map { list -> list.first { it.model == TranscriptionModel.DEFAULT }.state }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun download() = models.install(TranscriptionModel.DEFAULT)

    fun finishSetupStep() {
        viewModelScope.launch { SetupStep.MODEL.finish(store) }
    }

    fun backSetupStep() {
        viewModelScope.launch { SetupStep.MODEL.back(store, steps) }
    }
}

/**
 * Offers the default speech recognition model with its size. The download runs in the background,
 * so the setup can be finished while it runs, or without it.
 */
@Composable
fun ModelSetupScreen(viewModel: ModelSetupViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val model = TranscriptionModel.DEFAULT

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepLabel(SetupStep.MODEL)
            Text(stringResource(R.string.setup_model_title), style = MaterialTheme.typography.headlineMedium)
            Text(stringResource(R.string.setup_model_text), color = MaterialTheme.colorScheme.onSurfaceVariant)

            val installed = state == ModelState.Installed
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (installed) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Icon(
                        painterResource(if (installed) R.drawable.ic_check_circle else R.drawable.ic_record_voice),
                        contentDescription = null,
                        tint = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.setup_model_name), style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${model.displayName} · " + stringResource(R.string.setup_model_size, fileSize(model.bytes)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        when (val current = state) {
                            null -> Unit
                            ModelState.Installed -> Text(
                                stringResource(R.string.model_installed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            ModelState.NotInstalled -> FilledTonalButton(onClick = viewModel::download) {
                                Text(stringResource(R.string.model_download))
                            }
                            is ModelState.Failed -> {
                                ModelProgress(current)
                                FilledTonalButton(onClick = viewModel::download) { Text(stringResource(R.string.model_try_again)) }
                            }
                            ModelState.Waiting, is ModelState.Downloading -> ModelProgress(current)
                        }
                    }
                }
            }

            Text(
                stringResource(R.string.setup_model_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = viewModel::backSetupStep) { Text(stringResource(R.string.back)) }
                Spacer(Modifier.weight(1f))
                Button(onClick = viewModel::finishSetupStep) { Text(stringResource(R.string.done)) }
            }
        }
    }
}
