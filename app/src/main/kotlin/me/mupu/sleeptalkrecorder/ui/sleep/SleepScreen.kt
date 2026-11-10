package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.playback.PlaybackKind
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.transcription.ModelChange
import me.mupu.sleeptalkrecorder.transcription.TranscriptionStatus
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.AppBarIcon
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import me.mupu.sleeptalkrecorder.ui.components.SegmentLabel
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatEventLength
import me.mupu.sleeptalkrecorder.ui.format.formatPosition
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import me.mupu.sleeptalkrecorder.ui.library.sleepSubtitle
import java.text.NumberFormat
import java.time.Duration
import java.time.ZoneId

/**
 * One sleep: its name and times, the zoomable loudness timeline with its overview, the player with
 * full and cut playback, and the list of its events, each of which plays on its own.
 *
 * The list highlights the event at the playhead and scrolls to it. Auto zoom, on until the user
 * pans or zooms the timeline by hand, frames that event and keeps the playhead in view.
 *
 * The sensitivity of the sleep is set from the app bar's menu, that around an event from the
 * event's menu, in [SensitivitySheet]. The event's menu also hides it, which a snackbar undoes; a
 * hidden event has an Unhide button on its row, and the timeline draws it grey like silence. A speech event's text can be
 * corrected, or the event marked as not speech, which a snackbar undoes; when the active model is
 * another than the one that made the sleep's transcripts, a hint offers to recognise them again.
 */
@Composable
fun SleepScreen(viewModel: SleepViewModel, onBack: () -> Unit, onOpenStats: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val filterKinds by viewModel.filterKinds.collectAsStateWithLifecycle()
    val modelChange by viewModel.modelChange.collectAsStateWithLifecycle()
    // Read only where it is drawn or shown, so the moving playhead recomposes nothing else.
    val positionState = viewModel.position.collectAsStateWithLifecycle()
    val position = remember(positionState) { { positionState.value } }
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()
    val snackbars = remember { SnackbarHostState() }

    // Playback has no notification or lock-screen controls, so it stops with the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }

    LaunchedEffect(viewModel) {
        viewModel.eventHidden.collect { event ->
            val result = snackbars.showSnackbar(
                resources.getString(R.string.event_hidden),
                actionLabel = resources.getString(R.string.undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.setHidden(event, hidden = false)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.speechMarked.collect { change ->
            val result = snackbars.showSnackbar(
                resources.getString(if (change.speech) R.string.marked_speech else R.string.marked_not_speech),
                actionLabel = resources.getString(R.string.undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.undoSpeechMark(change)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.sensitivityApplied.collect { applied ->
            val message = if (applied.wholeSleep) R.plurals.sensitivity_applied_sleep else R.plurals.sensitivity_applied_event
            snackbars.showSnackbar(resources.getQuantityString(message, applied.events, applied.events))
        }
    }

    val sleep = state.sleep
    val title = sleep?.let { sleepTitle(resources, it.date) }.orEmpty()
    Scaffold(
        topBar = {
            AppBar(
                title,
                navigationIcon = { BackIcon(onBack) },
                actions = {
                    SpeechRecognitionStatus(transcription, state.events, modelChange, onRun = viewModel::runSpeechRecognition)
                    AppBarIcon(R.drawable.ic_bar_chart, stringResource(R.string.stats_of_sleep), onOpenStats)
                    MoreMenu(
                        listOfNotNull(
                            MenuEntry(R.string.sensitivity_sleep, R.drawable.ic_tune) { viewModel.openSensitivity(null) }
                                .takeIf { state.sensitivityAvailable },
                            MenuEntry(
                                if (transcription.nothingRecognised) R.string.run_speech_recognition else R.string.run_speech_recognition_again,
                                R.drawable.ic_subtitles,
                                enabled = transcription.canRun,
                                onClick = viewModel::runSpeechRecognition,
                            ).takeUnless { transcription == TranscriptionStatus.Off },
                        ),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sleep != null) {
                Text(
                    listOfNotNull(sleepSubtitle(sleep, zone), eventSummary(sleep)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            modelChange?.let { change ->
                ModelChangeHint(
                    change,
                    onRerun = viewModel::runSpeechRecognition,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, end = 12.dp),
                )
            }
            Spacer(Modifier.height(8.dp))

            val timeline = state.timeline
            val player = state.player
            if (timeline != null && player != null) {
                var window by remember(timeline) { mutableStateOf(TimelineWindow.whole(timeline.clockDuration)) }
                var autoZoom by remember { mutableStateOf(true) }
                var correcting by remember { mutableStateOf<SleepEvent?>(null) }

                val current = state.currentEvent
                LaunchedEffect(autoZoom, current?.id) {
                    if (autoZoom && current != null) window = window.framing(timeline.clockOf(current.start), timeline.clockOf(current.end))
                }
                LaunchedEffect(autoZoom, player.playing, timeline) {
                    if (autoZoom && player.playing) {
                        snapshotFlow { positionState.value }.collect { at ->
                            val followed = window.following(timeline.clockOf(at))
                            if (followed != window) window = followed
                        }
                    }
                }

                Column(Modifier.padding(horizontal = 12.dp)) {
                    LoudnessTimeline(
                        timeline = timeline,
                        loudness = state.loudness,
                        events = state.timelineEvents,
                        unavailable = state.unavailable,
                        position = position,
                        window = window,
                        onWindowChange = { change ->
                            window = change(window)
                            autoZoom = false
                        },
                        autoZoom = autoZoom,
                        onAutoZoomChange = { autoZoom = it },
                        onSeek = viewModel::seekTo,
                        onScrub = viewModel::scrub,
                        onScrubFinished = viewModel::finishScrub,
                    )
                    Player(
                        player,
                        timeline,
                        position,
                        onTogglePlay = viewModel::togglePlay,
                        onNextSpeed = viewModel::nextSpeed,
                        onCutChange = viewModel::setCut,
                    )
                }
                HorizontalDivider()
                if (state.events.isNotEmpty()) {
                    EventFilterChips(filterKinds, state.events, state.filter, onSelect = viewModel::setFilter)
                }
                EventList(
                    sleep = sleep,
                    events = state.listedEvents,
                    filter = state.filter,
                    timeline = timeline,
                    currentEventId = current?.id,
                    playingEventId = state.playingEventId,
                    onSelect = { event ->
                        if (!autoZoom) window = window.showing(timeline.clockOf(event.start), timeline.clockOf(event.end))
                        viewModel.seekToEvent(event)
                    },
                    onTogglePlay = viewModel::togglePlayEvent,
                    onHide = viewModel::setHidden,
                    onSensitivity = if (state.sensitivityAvailable) viewModel::openSensitivity else null,
                    modelChange = modelChange,
                    onCorrect = { correcting = it },
                    onMarkSpeech = viewModel::markSpeech,
                    onRerun = viewModel::runSpeechRecognition,
                )
                correcting?.let { event ->
                    CorrectTranscriptDialog(
                        event,
                        onSave = { text -> viewModel.correctTranscript(event, text) },
                        onDismiss = { correcting = null },
                    )
                }

                sensitivity?.let { sheet ->
                    SensitivitySheet(
                        state = sheet,
                        timeline = timeline,
                        position = position,
                        onThreshold = viewModel::setSensitivity,
                        onReset = viewModel::resetSensitivity,
                        onListen = viewModel::listenToSensitivity,
                        onApply = viewModel::applySensitivity,
                        onDismiss = viewModel::closeSensitivity,
                    )
                }
            }
        }
    }
}

/** An entry of a [MoreMenu]; its [label] is a string resource, read only while the menu is open. */
private class MenuEntry(@StringRes val label: Int, @DrawableRes val icon: Int, val enabled: Boolean = true, val onClick: () -> Unit)

/** A "more options" button with the menu [entries]; nothing without entries. */
@Composable
private fun MoreMenu(entries: List<MenuEntry>) {
    if (entries.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more_options))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (entry in entries) {
                DropdownMenuItem(
                    text = { Text(stringResource(entry.label)) },
                    leadingIcon = { Icon(painterResource(entry.icon), contentDescription = null) },
                    enabled = entry.enabled,
                    onClick = {
                        open = false
                        entry.onClick()
                    },
                )
            }
        }
    }
}

/** "12 events", that they are still being found, or null while the sleep is recorded. */
@Composable
private fun eventSummary(sleep: SleepSummary): String? = when {
    sleep.isRunning -> null
    !sleep.eventsFound -> stringResource(R.string.finding_events)
    else -> pluralStringResource(R.plurals.event_count, sleep.eventCount, sleep.eventCount)
}

@Composable
private fun Player(
    player: PlayerUiState,
    timeline: SleepTimeline,
    position: () -> Duration,
    onTogglePlay: () -> Unit,
    onNextSpeed: () -> Unit,
    onCutChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        FilledIconButton(
            onClick = onTogglePlay,
            enabled = player.hasAudio && !player.cutIsEmpty,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(52.dp),
        ) {
            Icon(
                painterResource(if (player.playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (player.playing) R.string.pause else R.string.play),
                modifier = Modifier.size(30.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        PlayerPosition(timeline, position, Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        PlaybackMode(cut = player.cut, enabled = player.hasAudio, onCutChange = onCutChange, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        SpeedChip(player.speed, player.speedOf, enabled = player.hasAudio, onClick = onNextSpeed)
    }

    val problem = when {
        !player.hasAudio -> R.string.sleep_no_audio
        player.failed -> R.string.playback_failed
        player.cutIsEmpty -> R.string.cut_nothing_to_play
        else -> null
    }
    if (problem != null) {
        Text(
            stringResource(problem),
            style = MaterialTheme.typography.bodyMedium,
            color = if (player.failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        )
    }
}

/**
 * The clock time and position of the playhead. It reads [position] itself, so only it recomposes
 * while the position moves.
 */
@Composable
private fun PlayerPosition(timeline: SleepTimeline, position: () -> Duration, modifier: Modifier = Modifier) {
    val clock = LocalClockFormatter.current
    val zone = ZoneId.systemDefault()
    val at = position()
    Column(modifier) {
        Text(
            if (timeline.pieces.isEmpty()) "" else clock.format(timeline.instantAt(at), zone, seconds = true),
            style = MaterialTheme.typography.titleLarge.merge(Numbers),
        )
        Text(
            stringResource(R.string.position_short, formatPosition(at)),
            style = MaterialTheme.typography.bodyMedium.merge(Numbers),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Full or cut playback, as two connected buttons. The fill alone marks the choice: the row shares
 * its width with the speed button, which leaves no room for a check mark beside "Nur Ereignisse".
 */
@Composable
private fun PlaybackMode(cut: Boolean, enabled: Boolean, onCutChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    // Equally high buttons, also when one label takes two lines.
    SingleChoiceSegmentedButtonRow(modifier.height(IntrinsicSize.Min)) {
        SegmentedButton(
            selected = !cut,
            onClick = { onCutChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            enabled = enabled,
            modifier = Modifier.fillMaxHeight(),
            icon = {},
        ) {
            SegmentLabel(stringResource(R.string.playback_full))
        }
        SegmentedButton(
            selected = cut,
            onClick = { onCutChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            enabled = enabled,
            modifier = Modifier.fillMaxHeight(),
            icon = {},
        ) {
            SegmentLabel(stringResource(R.string.playback_cut))
        }
    }
}

/**
 * The events the [filter] shows, in order; a tap shows one on the timeline, its button plays it
 * alone, a hidden one has a button that unhides it, and its menu hides it, opens the sensitivity around it while [onSensitivity]
 * is given, and for a speech event corrects its text or marks it as not speech. The event at the
 * playhead is highlighted and scrolled into view.
 */
@Composable
private fun EventList(
    sleep: SleepSummary?,
    events: List<SleepEvent>,
    filter: EventFilter,
    timeline: SleepTimeline,
    currentEventId: Long?,
    playingEventId: Long?,
    onSelect: (SleepEvent) -> Unit,
    onTogglePlay: (SleepEvent) -> Unit,
    onHide: (SleepEvent, Boolean) -> Unit,
    onSensitivity: ((SleepEvent) -> Unit)?,
    modelChange: ModelChange?,
    onCorrect: (SleepEvent) -> Unit,
    onMarkSpeech: (SleepEvent, Boolean) -> Unit,
    onRerun: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(currentEventId) {
        val index = events.indexOfFirst { it.id == currentEventId }
        if (index < 0) return@LaunchedEffect
        val layout = listState.layoutInfo
        val row = layout.visibleItemsInfo.firstOrNull { it.index == index }
        val fullyVisible = row != null && row.offset >= layout.viewportStartOffset &&
            row.offset + row.size <= layout.viewportEndOffset
        if (!fullyVisible) {
            // Centred in the list, so the events around it are seen too.
            val rowSize = row?.size ?: layout.visibleItemsInfo.firstOrNull()?.size ?: 0
            listState.animateScrollToItem(index, scrollOffset = -(layout.viewportSize.height - rowSize) / 2)
        }
    }

    var expanded by remember { mutableStateOf(emptySet<Long>()) }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(events, key = { it.id }) { event ->
            EventRow(
                event,
                timeline,
                current = event.id == currentEventId,
                playing = event.id == playingEventId,
                transcriptExpanded = event.id in expanded,
                onToggleTranscript = { expanded = if (event.id in expanded) expanded - event.id else expanded + event.id },
                onSelect = onSelect,
                onTogglePlay = onTogglePlay,
                onHide = onHide,
                onSensitivity = onSensitivity,
                modelChange = modelChange,
                onCorrect = onCorrect,
                onMarkSpeech = onMarkSpeech,
                onRerun = onRerun,
            )
        }
        if (sleep != null && events.isEmpty()) {
            item(key = "empty") {
                val empty = when {
                    !sleep.eventsFound -> R.string.finding_events
                    filter == EventFilter.Hidden -> R.string.no_hidden_events
                    filter != EventFilter.All -> R.string.no_events_filter
                    else -> R.string.no_events
                }
                Text(
                    stringResource(empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
    }
}

@Composable
private fun EventRow(
    event: SleepEvent,
    timeline: SleepTimeline,
    current: Boolean,
    playing: Boolean,
    transcriptExpanded: Boolean,
    onToggleTranscript: () -> Unit,
    onSelect: (SleepEvent) -> Unit,
    onTogglePlay: (SleepEvent) -> Unit,
    onHide: (SleepEvent, Boolean) -> Unit,
    onSensitivity: ((SleepEvent) -> Unit)?,
    modelChange: ModelChange?,
    onCorrect: (SleepEvent) -> Unit,
    onMarkSpeech: (SleepEvent, Boolean) -> Unit,
    onRerun: () -> Unit,
) {
    val clock = LocalClockFormatter.current
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val time = if (timeline.pieces.isEmpty()) "" else clock.format(timeline.instantAt(event.start), seconds = true)
    // A hidden event is dimmed, with the grey of silence on the timeline as its colour.
    val barColor = if (event.hidden) colors.outlineVariant else kindColor(event.kindColor, colors)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable { onSelect(event) }
            .background(if (current || playing) colors.primary.copy(alpha = 0.1f) else colors.surface)
            // The colour bar along the row's height, drawn rather than laid out, which would need
            // the row's intrinsic height and so measure every row twice.
            .drawBehind {
                val inset = 10.dp.toPx()
                drawRoundRect(
                    barColor,
                    topLeft = Offset(ROW_START.toPx(), inset),
                    size = Size(BAR_WIDTH.toPx(), (size.height - 2 * inset).coerceAtLeast(0f)),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
            .padding(start = ROW_START, end = 4.dp),
    ) {
        Spacer(Modifier.width(BAR_WIDTH + 12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp).alpha(if (event.hidden) 0.6f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.event_heading, time, formatEventLength(resources, event.end - event.start)),
                    style = MaterialTheme.typography.titleSmall.merge(Numbers),
                )
                if (event.hidden) HiddenTag()
            }
            Text(
                stringResource(R.string.event_details, kindName(event.kindId, event.kindName), formatPosition(event.start)),
                style = MaterialTheme.typography.bodySmall.merge(Numbers),
                color = colors.onSurfaceVariant,
            )
            TranscriptLine(
                event,
                transcriptExpanded,
                onToggleTranscript,
                modelChange,
                onRerun = onRerun,
            )
        }
        if (event.hidden) {
            TextButton(onClick = { onHide(event, false) }) {
                Icon(painterResource(R.drawable.ic_visibility), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.unhide_event))
            }
        }
        IconButton(onClick = { onTogglePlay(event) }) {
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (playing) R.string.pause_event else R.string.play_event),
            )
        }
        MoreMenu(
            listOfNotNull(
                MenuEntry(R.string.hide_event, R.drawable.ic_visibility_off) { onHide(event, true) }.takeUnless { event.hidden },
                onSensitivity?.let { MenuEntry(R.string.sensitivity_here, R.drawable.ic_tune) { it(event) } },
                MenuEntry(R.string.correct_text, R.drawable.ic_edit) { onCorrect(event) }.takeIf { event.isSpeech },
                MenuEntry(R.string.not_speech_plain_event, R.drawable.ic_voice_over_off) { onMarkSpeech(event, false) }.takeIf { event.isSpeech },
                MenuEntry(R.string.mark_speech, R.drawable.ic_record_voice) { onMarkSpeech(event, true) }.takeUnless { event.isSpeech },
            ),
        )
    }
}

/** Tabular figures, so times and positions keep their width as they change. */
private val Numbers = TextStyle(fontFeatureSettings = "tnum")

/** The space before an event row's colour bar, and the bar's width. */
private val ROW_START = 16.dp
private val BAR_WIDTH = 4.dp

/** The small "hidden" tag after a hidden event's time. */
@Composable
private fun HiddenTag() {
    Text(
        stringResource(R.string.hidden_tag),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}

/**
 * Shows the speed of what plays now and switches it to the next one on every tap; while a single
 * event plays on its own, that is the event speed, which the chip says.
 */
@Composable
private fun SpeedChip(speed: Float, speedOf: PlaybackKind, enabled: Boolean, onClick: () -> Unit) {
    val resources = LocalResources.current
    val number = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply {
        maximumFractionDigits = 2
    }
    val hint = stringResource(if (speedOf == PlaybackKind.EVENT) R.string.speed_hint_event else R.string.speed_hint)
    val label = when (speedOf) {
        PlaybackKind.SLEEP -> stringResource(R.string.speed_value, number.format(speed))
        PlaybackKind.EVENT -> stringResource(R.string.speed_value_event, number.format(speed))
    }
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_speed), contentDescription = null) },
        modifier = Modifier.semantics { contentDescription = hint },
    )
}
