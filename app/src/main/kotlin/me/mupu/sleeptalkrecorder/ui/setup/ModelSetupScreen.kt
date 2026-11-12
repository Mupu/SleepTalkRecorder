package me.mupu.sleeptalkrecorder.ui.setup

import androidx.annotation.StringRes
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
import me.mupu.sleeptalkrecorder.models.DownloadableModel
import me.mupu.sleeptalkrecorder.models.ModelLibrary
import me.mupu.sleeptalkrecorder.models.ModelState
import me.mupu.sleeptalkrecorder.models.SoundModel
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.ui.components.ModelProgress
import me.mupu.sleeptalkrecorder.ui.components.fileSize
import me.mupu.sleeptalkrecorder.ui.settings.purpose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** A model the setup offers, with what it is for. */
data class OfferedModel(val model: DownloadableModel, @StringRes val purpose: Int, val state: ModelState)

/**
 * State and actions of the setup page that offers the recommended models as one download: the
 * default speech recognition model and the sound models of the second way of finding events.
 */
class ModelSetupViewModel(
    private val store: SettingsStore,
    private val models: ModelLibrary,
    /** The steps of this build's setup, which decide the step before this one. */
    private val steps: List<SetupStep>,
) : ViewModel() {

    /** The offered models with their states; null until they are read. */
    val state: StateFlow<List<OfferedModel>?> = combine(models.models, models.soundModels) { speech, sound ->
        listOf(OfferedModel(TranscriptionModel.DEFAULT, R.string.setup_purpose_speech, speech.first { it.model == TranscriptionModel.DEFAULT }.state)) +
            SoundModel.RECOMMENDED.map { model -> OfferedModel(model, model.purpose, sound.first { it.model == model }.state) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Downloads every offered model that is not installed yet. */
    fun download() {
        for (offered in state.value.orEmpty()) {
            if (offered.state == ModelState.NotInstalled || offered.state is ModelState.Failed) models.install(offered.model)
        }
    }

    fun finishSetupStep() {
        viewModelScope.launch { SetupStep.MODEL.finish(store) }
    }

    fun backSetupStep() {
        viewModelScope.launch { SetupStep.MODEL.back(store, steps) }
    }
}

/**
 * Offers the recommended models as one download, each with a line on what it is for and the size
 * of all together. The downloads run in the background, so the setup can be finished while they
 * run, or without them.
 */
@Composable
fun ModelSetupScreen(viewModel: ModelSetupViewModel) {
    val offered by viewModel.state.collectAsStateWithLifecycle()

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

            val models = offered.orEmpty()
            val allInstalled = models.isNotEmpty() && models.all { it.state == ModelState.Installed }
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = if (allInstalled) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (model in models) OfferedModelRow(model)
                    if (models.isNotEmpty()) {
                        Text(
                            stringResource(R.string.setup_model_size, fileSize(models.sumOf { it.model.bytes })),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    when {
                        models.isEmpty() || allInstalled -> Unit
                        models.any { it.state is ModelState.Failed } ->
                            FilledTonalButton(onClick = viewModel::download) { Text(stringResource(R.string.model_try_again)) }
                        models.any { it.state == ModelState.NotInstalled } ->
                            FilledTonalButton(onClick = viewModel::download) { Text(stringResource(R.string.model_download)) }
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

/** One offered model: its name, what it is for, and its download's state. */
@Composable
private fun OfferedModelRow(offered: OfferedModel) {
    val installed = offered.state == ModelState.Installed
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(
            painterResource(if (installed) R.drawable.ic_check_circle else R.drawable.ic_record_voice),
            contentDescription = null,
            tint = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(offered.model.displayName, style = MaterialTheme.typography.titleMedium)
            Text(
                stringResource(offered.purpose) + " · " + fileSize(offered.model.bytes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (val current = offered.state) {
                ModelState.Installed -> Text(
                    stringResource(R.string.model_installed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ModelState.NotInstalled -> Unit
                is ModelState.Failed, ModelState.Waiting, is ModelState.Downloading -> ModelProgress(current)
            }
        }
    }
}
