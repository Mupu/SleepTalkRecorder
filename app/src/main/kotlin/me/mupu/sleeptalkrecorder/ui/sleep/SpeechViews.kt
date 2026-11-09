package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.AnnotationOrigin
import me.mupu.sleeptalkrecorder.data.EventKindEntity
import me.mupu.sleeptalkrecorder.data.EventKinds
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.transcription.TranscriptionStatus
import me.mupu.sleeptalkrecorder.transcription.WaitReason

/** Whether speech recognition can be started on the sleep: it has events and none is being recognised. */
internal val TranscriptionStatus.canRun: Boolean
    get() = this != TranscriptionStatus.NoEvents && this !is TranscriptionStatus.Running

/**
 * The top bar's speech recognition status: a spinner while it runs on the sleep, an hourglass
 * while events wait and a check mark once all are done, which each open what is running.
 */
@Composable
internal fun SpeechRecognitionStatus(status: TranscriptionStatus, events: List<SleepEvent>, onRun: () -> Unit) {
    var showing by remember { mutableStateOf(false) }
    val label = stringResource(R.string.settings_transcription)

    if (status != TranscriptionStatus.NoEvents) {
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
        SpeechRecognitionDialog(status, events, onRun = onRun, onDismiss = { showing = false })
    }
}

/** What speech recognition does with the sleep: running, done, or waiting and why. */
@Composable
private fun SpeechRecognitionDialog(
    status: TranscriptionStatus,
    events: List<SleepEvent>,
    onRun: () -> Unit,
    onDismiss: () -> Unit,
) {
    val model = events.mapNotNull { it.transcriptProducer }
        .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        ?.let { producer -> TranscriptionModel.entries.find { it.id == producer }?.displayName ?: producer }

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
                    Text(stringResource(if (status == TranscriptionStatus.Done) R.string.transcription_run_again else R.string.transcription_run_now))
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

/** "All 12" and one "Speech only 3" chip per kind with a filter; null [selected] shows all. */
@Composable
internal fun EventFilterChips(
    kinds: List<EventKindEntity>,
    events: List<SleepEvent>,
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
    ) {
        FilterChip(
            selected = selected == null,
            onClick = { onSelect(null) },
            label = { Text("${stringResource(R.string.filter_all)}  ${events.size}") },
        )
        for (kind in kinds) {
            FilterChip(
                selected = selected == kind.id,
                onClick = { onSelect(if (selected == kind.id) null else kind.id) },
                label = { Text("${stringResource(R.string.filter_kind_only, kindName(kind.id, kind.name))}  ${events.count { it.kindId == kind.id }}") },
            )
        }
    }
}

/**
 * What speech recognition found in a speech event: for a murmur that no words were recognised;
 * else its transcript, one line until tapped, then whole and marked as automatic.
 */
@Composable
internal fun TranscriptLine(event: SleepEvent, expanded: Boolean, onToggle: () -> Unit) {
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
            if (expanded && event.transcriptOrigin != AnnotationOrigin.USER) {
                val model = TranscriptionModel.entries.find { it.id == event.transcriptProducer }?.displayName ?: event.transcriptProducer.orEmpty()
                Text(stringResource(R.string.transcript_automatic, model), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        Icon(
            painterResource(if (expanded) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
            contentDescription = stringResource(R.string.transcript),
            tint = colors.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
    }
}

/** The name of an event kind: the user's [name], or the app's for the kinds it ships. */
@Composable
internal fun kindName(id: String, name: String?): String = name ?: when (id) {
    EventKinds.EVENT -> stringResource(R.string.kind_event)
    EventKinds.SPEECH -> stringResource(R.string.kind_speech)
    else -> id
}
