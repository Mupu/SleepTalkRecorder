package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.EventKindEntity
import me.mupu.sleeptalkrecorder.data.EventKinds
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.transcription.ModelChange
import me.mupu.sleeptalkrecorder.transcription.TranscriptionStatus
import me.mupu.sleeptalkrecorder.transcription.WaitReason

/**
 * Whether speech recognition can be started on the sleep: it is on, the sleep has events and none
 * is being recognised.
 */
internal val TranscriptionStatus.canRun: Boolean
    get() = this != TranscriptionStatus.NoEvents && this != TranscriptionStatus.Off && this !is TranscriptionStatus.Running

/** Whether speech recognition has not yet recognised any event of the sleep. */
internal val TranscriptionStatus.nothingRecognised: Boolean
    get() = this is TranscriptionStatus.Waiting && pending == events

/**
 * The top bar's speech recognition status: a spinner while it runs on the sleep, an hourglass
 * while events wait and a check mark once all are done, which each open what is running; nothing
 * while speech recognition is off.
 */
@Composable
internal fun SpeechRecognitionStatus(status: TranscriptionStatus, events: List<SleepEvent>, modelChange: ModelChange?, onRun: () -> Unit) {
    var showing by remember { mutableStateOf(false) }
    val label = stringResource(R.string.settings_transcription)

    if (status != TranscriptionStatus.NoEvents && status != TranscriptionStatus.Off) {
        IconButton(onClick = { showing = true }) {
            when (status) {
                // Turning until the first event is done, which includes loading the model.
                is TranscriptionStatus.Running -> if (status.done == 0) {
                    CircularProgressIndicator(strokeWidth = 3.dp, modifier = Modifier.size(22.dp))
                } else {
                    CircularProgressIndicator(
                        progress = { status.done / status.total.toFloat() },
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(22.dp),
                    )
                }
                is TranscriptionStatus.Waiting -> Icon(painterResource(R.drawable.ic_hourglass), contentDescription = label)
                else -> Icon(painterResource(R.drawable.ic_check_circle), contentDescription = label)
            }
        }
    }
    if (showing) {
        SpeechRecognitionDialog(status, events, modelChange, onRun = onRun, onDismiss = { showing = false })
    }
}

/**
 * What speech recognition does with the sleep: running, done, or waiting and why; after a
 * [modelChange] it offers the re-run with the active model.
 */
@Composable
private fun SpeechRecognitionDialog(
    status: TranscriptionStatus,
    events: List<SleepEvent>,
    modelChange: ModelChange?,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    val model = events.mapNotNull { it.transcriptProducer }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        ?.let(::modelName)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_subtitles), contentDescription = null) },
        title = { Text(stringResource(R.string.settings_transcription)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (status) {
                    is TranscriptionStatus.Running ->
                        Text(stringResource(R.string.transcription_running, (status.done + 1).coerceAtMost(status.total), status.total))
                    is TranscriptionStatus.Waiting -> {
                        Text(pluralStringResource(R.plurals.transcription_pending, status.events, status.pending, status.events))
                        Text(stringResource(status.reason.text))
                    }
                    else -> Text(stringResource(R.string.transcription_done, events.count { it.isSpeech }, events.size))
                }
                if (model != null) Text(stringResource(R.string.transcription_model, model), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
        dismissButton = if (status is TranscriptionStatus.Running) {
            null
        } else {
            {
                TextButton(
                    onClick = {
                        onDismiss()
                        onRun()
                    },
                ) {
                    Text(
                        when {
                            modelChange != null -> stringResource(R.string.rerun_with, modelChange.active.displayName)
                            status == TranscriptionStatus.Done -> stringResource(R.string.transcription_run_again)
                            else -> stringResource(R.string.transcription_run_now)
                        },
                    )
                }
            }
        },
    )
}

private val WaitReason.text: Int
    get() = when (this) {
        WaitReason.QUEUE -> R.string.transcription_waits_queue
        WaitReason.MODEL -> R.string.transcription_waits_model
        WaitReason.MANUAL -> R.string.transcription_waits_manual
        WaitReason.CHARGER -> R.string.transcription_waits_charger
        WaitReason.BATTERY -> R.string.transcription_waits_battery
    }

/**
 * The exclusive filter chips with the number of events each lists: "All 12", one "Speech only 3"
 * per kind with a filter, and "Hidden 2". Tapping the selected kind or "Hidden" again shows all.
 */
@Composable
internal fun EventFilterChips(
    kinds: List<EventKindEntity>,
    events: List<SleepEvent>,
    selected: EventFilter,
    onSelect: (EventFilter) -> Unit,
) {
    @Composable
    fun Chip(filter: EventFilter, label: String) {
        FilterChip(
            selected = selected == filter,
            onClick = { onSelect(if (selected == filter) EventFilter.All else filter) },
            label = { Text("$label  ${events.count(filter::shows)}") },
        )
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        Chip(EventFilter.All, stringResource(R.string.filter_all))
        for (kind in kinds) {
            Chip(EventFilter.Kind(kind.id), stringResource(R.string.filter_kind_only, kindName(kind.id, kind.name)))
        }
        Chip(EventFilter.Hidden, stringResource(R.string.filter_hidden))
    }
}

/**
 * What speech recognition found in a speech event: for a murmur that no words were recognised;
 * else its transcript, one line until tapped, then whole, marked as automatic or as corrected by
 * the user; correcting it and saying it is not speech are in the event's menu. After a
 * [modelChange] an automatic transcript also offers the re-run with the active model.
 */
@Composable
internal fun TranscriptLine(
    event: SleepEvent,
    expanded: Boolean,
    onToggle: () -> Unit,
    modelChange: ModelChange?,
    onRerun: () -> Unit,
) {
    if (!event.isSpeech) return
    val colors = MaterialTheme.colorScheme
    val transcript = event.transcript
    if (transcript == null) {
        Text(
            stringResource(R.string.murmur),
            style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = colors.onSurfaceVariant,
        )
        return
    }

    Row(verticalAlignment = Alignment.Top, modifier = Modifier.clickable(onClick = onToggle).padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.transcript_quoted, transcript),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.tertiary,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Text(
                    if (event.corrected) {
                        stringResource(R.string.transcript_corrected)
                    } else {
                        stringResource(R.string.transcript_automatic, event.transcriptProducer?.let(::modelName).orEmpty())
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
            }
        }
        Icon(
            painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
            contentDescription = stringResource(R.string.transcript),
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
    if (expanded) {
        if (modelChange != null && !event.corrected) {
            FilledTonalButton(onClick = onRerun, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                Icon(painterResource(R.drawable.ic_subtitles), contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.rerun_with, modelChange.active.displayName))
            }
        }
    }
}

/**
 * Asks for the words said in speech event [event], filled with its transcript; [onSave] gets the
 * text as written, blank for a murmur without words.
 */
@Composable
internal fun CorrectTranscriptDialog(event: SleepEvent, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable(event.id) { mutableStateOf(event.transcript.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.correct_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 2,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.correct_text_hint), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onDismiss()
                    onSave(text)
                },
            ) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The hint that the sleep's transcripts were made by another model than the active one, with the
 * button that recognises its speech again with the active one.
 */
@Composable
internal fun ModelChangeHint(change: ModelChange, onRerun: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.primaryContainer,
        contentColor = colors.onPrimaryContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier,
    ) {
        Column(Modifier.padding(start = 12.dp, top = 10.dp, end = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_subtitles), contentDescription = null)
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(R.string.model_change_hint, modelName(change.previous), change.active.displayName),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
            }
            TextButton(onClick = onRerun, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.rerun_with, change.active.displayName))
            }
        }
    }
}

/** The name of the model with the id [producer], or the id of a model the app no longer knows. */
internal fun modelName(producer: String): String =
    TranscriptionModel.entries.find { it.id == producer }?.displayName ?: producer

/** The name of an event kind: the user's [name], or the app's for the kinds it ships. */
@Composable
internal fun kindName(id: String, name: String?): String = name ?: when (id) {
    EventKinds.EVENT -> stringResource(R.string.kind_event)
    EventKinds.SPEECH -> stringResource(R.string.kind_speech)
    else -> id
}
