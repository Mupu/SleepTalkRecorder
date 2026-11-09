package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.annotation.DrawableRes
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
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
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatEventLength
import me.mupu.sleeptalkrecorder.ui.format.formatPosition
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import me.mupu.sleeptalkrecorder.ui.library.sleepSubtitle
import java.text.NumberFormat
import java.time.ZoneId

/**
 * One sleep: its name and times, the zoomable loudness timeline with its overview, the player with
 * full and cut playback, and the list of its events, each of which plays on its own.
 *
 * The list highlights the event at the playhead and scrolls to it. Auto zoom, on until the user
 * pans or zooms the timeline by hand, frames that event and keeps the playhead in view.
 *
 * The sensitivity of the sleep is set from the app bar's menu, that around an event from the
 * event's menu, in [SensitivitySheet]. The event's menu also hides it, which a snackbar undoes, or
 * unhides it; the timeline draws hidden events grey like silence.
 */
@Composable
fun SleepScreen(viewModel: SleepViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val filterKinds by viewModel.filterKinds.collectAsStateWithLifecycle()
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
        viewModel.sensitivityApplied.collect { applied ->
            val message = if (applied.wholeSleep) R.plurals.sensitivity_applied_sleep else R.plurals.sensitivity_applied_event
            snackbars.showSnackbar(resources.getQuantityString(message, applied.events, applied.events))
        }
    }

    val sleep = state.sleep
    val title = sleep?.let { sleepTitle(resources, SleepDate.of(it.startedAt, zone)) }.orEmpty()
    Scaffold(
        topBar = {
            AppBar(
                title,
                navigationIcon = { BackIcon(onBack) },
                actions = {
                    SpeechRecognitionStatus(transcription, state.events, onRun = viewModel::runSpeechRecognition)
                    MoreMenu(
                        listOfNotNull(
                            MenuEntry(stringResource(R.string.sensitivity_sleep), R.drawable.ic_tune) { viewModel.openSensitivity(null) }
                                .takeIf { state.sensitivityAvailable },
                            MenuEntry(
                                stringResource(R.string.run_speech_recognition),
                                R.drawable.ic_subtitles,
                                enabled = transcription.canRun,
                                onClick = viewModel::runSpeechRecognition,
                            ),
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
            Spacer(Modifier.height(8.dp))

            val timeline = state.timeline
            val player = state.player
            if (timeline != null && player != null) {
                var window by remember(timeline) { mutableStateOf(TimelineWindow.whole(timeline.clockDuration)) }
                var autoZoom by remember { mutableStateOf(true) }

                val current = state.currentEvent
                LaunchedEffect(autoZoom, current?.id) {
                    if (autoZoom && current != null) window = window.framing(timeline.clockOf(current.start), timeline.clockOf(current.end))
                }
                if (autoZoom && player.playing) {
                    val followed = window.following(timeline.clockOf(player.position))
                    if (followed != window) SideEffect { window = followed }
                }

                Column(Modifier.padding(horizontal = 12.dp)) {
                    LoudnessTimeline(
                        timeline = timeline,
                        loudness = state.loudness,
                        events = state.timelineEvents,
                        unavailable = state.unavailable,
                        position = player.position,
                        window = window,
                        onWindowChange = {
                            window = it
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
                )

                sensitivity?.let { sheet ->
                    SensitivitySheet(
                        state = sheet,
                        timeline = timeline,
                        position = player.position,
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

/** An entry of a [MoreMenu]. */
private class MenuEntry(val label: String, @DrawableRes val icon: Int, val enabled: Boolean = true, val onClick: () -> Unit)

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
                    text = { Text(entry.label) },
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
private fun Player(player: PlayerUiState, onTogglePlay: () -> Unit, onNextSpeed: () -> Unit, onCutChange: (Boolean) -> Unit) {
    val clock = LocalClockFormatter.current
    val zone = ZoneId.systemDefault()
    val numbers = TextStyle(fontFeatureSettings = "tnum")

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
        Column(Modifier.weight(1f)) {
            Text(
                player.clock?.let { clock.format(it, zone, seconds = true) }.orEmpty(),
                style = MaterialTheme.typography.titleLarge.merge(numbers),
            )
            Text(
                stringResource(R.string.position_short, formatPosition(player.position)),
                style = MaterialTheme.typography.bodyMedium.merge(numbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        PlaybackMode(cut = player.cut, enabled = player.hasAudio, onCutChange = onCutChange, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        SpeedChip(player.speed, enabled = player.hasAudio, onClick = onNextSpeed)
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

/** Full or cut playback, as two connected buttons. */
@Composable
private fun PlaybackMode(cut: Boolean, enabled: Boolean, onCutChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier) {
        SegmentedButton(
            selected = !cut,
            onClick = { onCutChange(false) },
            shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            enabled = enabled,
        ) {
            Text(stringResource(R.string.playback_full))
        }
        SegmentedButton(
            selected = cut,
            onClick = { onCutChange(true) },
            shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            enabled = enabled,
            icon = {
                SegmentedButtonDefaults.Icon(active = cut) {
                    Icon(painterResource(R.drawable.ic_content_cut), contentDescription = null, modifier = Modifier.size(18.dp))
                }
            },
        ) {
            Text(stringResource(R.string.playback_cut))
        }
    }
}

/**
 * The events the [filter] shows, in order; a tap shows one on the timeline, its button plays it
 * alone, and its menu hides or unhides it and opens the sensitivity around it while
 * [onSensitivity] is given. The event at the playhead is highlighted and scrolled into view.
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
) {
    val clock = LocalClockFormatter.current
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val numbers = TextStyle(fontFeatureSettings = "tnum")
    val time = if (timeline.pieces.isEmpty()) "" else clock.format(timeline.instantAt(event.start), seconds = true)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 64.dp)
            .clickable { onSelect(event) }
            .background(if (current || playing) colors.primary.copy(alpha = 0.1f) else colors.surface)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        // A hidden event is dimmed, with the grey of silence on the timeline as its colour.
        Box(
            Modifier
                .padding(vertical = 10.dp)
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(if (event.hidden) colors.outlineVariant else kindColor(event.kindColor, colors)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(vertical = 8.dp).alpha(if (event.hidden) 0.6f else 1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.event_heading, time, formatEventLength(resources, event.end - event.start)),
                    style = MaterialTheme.typography.titleSmall.merge(numbers),
                )
                if (event.hidden) HiddenTag()
            }
            Text(
                stringResource(R.string.event_details, kindName(event.kindId, event.kindName), formatPosition(event.start)),
                style = MaterialTheme.typography.bodySmall.merge(numbers),
                color = colors.onSurfaceVariant,
            )
            TranscriptLine(event, transcriptExpanded, onToggleTranscript)
        }
        IconButton(onClick = { onTogglePlay(event) }) {
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (playing) R.string.pause_event else R.string.play_event),
            )
        }
        MoreMenu(
            listOfNotNull(
                if (event.hidden) {
                    MenuEntry(stringResource(R.string.unhide_event), R.drawable.ic_visibility) { onHide(event, false) }
                } else {
                    MenuEntry(stringResource(R.string.hide_event), R.drawable.ic_visibility_off) { onHide(event, true) }
                },
                onSensitivity?.let { MenuEntry(stringResource(R.string.sensitivity_here), R.drawable.ic_tune) { it(event) } },
            ),
        )
    }
}

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

/** Shows the speed and switches to the next one on every tap. */
@Composable
private fun SpeedChip(speed: Float, enabled: Boolean, onClick: () -> Unit) {
    val resources = LocalResources.current
    val number = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply {
        maximumFractionDigits = 2
    }
    val hint = stringResource(R.string.speed_hint)
    AssistChip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(stringResource(R.string.speed_value, number.format(speed))) },
        leadingIcon = { Icon(painterResource(R.drawable.ic_speed), contentDescription = null) },
        modifier = Modifier.semantics { contentDescription = hint },
    )
}
