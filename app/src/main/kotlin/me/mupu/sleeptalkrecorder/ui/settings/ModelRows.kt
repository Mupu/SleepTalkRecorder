package me.mupu.sleeptalkrecorder.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.models.ModelState
import me.mupu.sleeptalkrecorder.models.ModelStatus
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.settings.TranscriptLanguage
import me.mupu.sleeptalkrecorder.ui.components.ModelProgress
import me.mupu.sleeptalkrecorder.ui.components.fileSize

/**
 * One transcription model: an installed model is chosen as the active one by tapping its row and
 * can be deleted; any other can be downloaded, and a running download cancelled.
 */
@Composable
internal fun ModelRow(
    status: ModelStatus,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onActivate: () -> Unit,
) {
    val installed = status.state == ModelState.Installed
    val details = buildList {
        add(stringResource(status.model.languages))
        add(fileSize(status.model.bytes))
        if (status.active) add(stringResource(R.string.model_active))
    }.joinToString(" · ")

    Column(if (installed) Modifier.selectable(status.active, role = Role.RadioButton, onClick = onActivate) else Modifier) {
        ListItem(
            headlineContent = { Text(status.model.displayName) },
            supportingContent = { Text(details) },
            leadingContent = {
                if (installed) {
                    RadioButton(selected = status.active, onClick = null)
                } else {
                    Icon(painterResource(R.drawable.ic_download), contentDescription = null)
                }
            },
            trailingContent = {
                when (status.state) {
                    ModelState.NotInstalled -> OutlinedButton(onClick = onInstall) { Text(stringResource(R.string.model_download)) }
                    is ModelState.Failed -> OutlinedButton(onClick = onInstall) { Text(stringResource(R.string.model_try_again)) }
                    ModelState.Waiting, is ModelState.Downloading -> IconButton(onClick = onCancel) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.model_cancel_download))
                    }
                    ModelState.Installed -> IconButton(onClick = onDelete) {
                        Icon(painterResource(R.drawable.ic_delete), contentDescription = stringResource(R.string.delete))
                    }
                }
            },
        )
        ModelProgress(status.state, Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp))
    }
}

/** Asks whether to delete [model], naming the space it frees. */
@Composable
internal fun DeleteModelDialog(model: TranscriptionModel, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.delete_model_title)) },
        text = { Text(stringResource(R.string.delete_model_text, model.displayName, fileSize(model.bytes))) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** The transcript language, shown with its value; tapping it opens a list to pick another. */
@Composable
internal fun TranscriptLanguageSetting(selected: TranscriptLanguage, onSelect: (TranscriptLanguage) -> Unit) {
    var picking by rememberSaveable { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_transcript_language)) },
        supportingContent = { Text(stringResource(R.string.settings_transcript_language_hint)) },
        leadingContent = { Icon(painterResource(R.drawable.ic_translate), contentDescription = null) },
        trailingContent = { Text(selected.label(), style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.clickable { picking = true },
    )

    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text(stringResource(R.string.settings_transcript_language)) },
            text = {
                Column(Modifier.selectableGroup()) {
                    TranscriptLanguage.entries.forEach { language ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.selectable(language == selected, role = Role.RadioButton) {
                                picking = false
                                onSelect(language)
                            },
                        ) {
                            RadioButton(selected = language == selected, onClick = null, modifier = Modifier.padding(12.dp).size(24.dp))
                            Text(language.label())
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun TranscriptLanguage.label(): String = nativeName ?: stringResource(R.string.transcript_language_app)

private val TranscriptionModel.languages: Int
    get() = when (this) {
        TranscriptionModel.PARAKEET_ULTRA -> R.string.model_languages_parakeet
        TranscriptionModel.WHISPER_SMALL -> R.string.model_languages_whisper
    }
