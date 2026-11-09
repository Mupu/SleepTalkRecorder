package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import me.mupu.sleeptalkrecorder.data.EventKinds
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
 */
@Composable
fun SleepScreen(viewModel: SleepViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()

    // Playback has no notification or lock-screen controls, so it stops with the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }

    val sleep = state.sleep
    val title = sleep?.let { sleepTitle(resources, SleepDate.of(it.startedAt, zone)) }.orEmpty()
    Scaffold(topBar = { AppBar(title, navigationIcon = { BackIcon(onBack) }) }) { padding ->
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
                        events = state.events,
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
                EventList(
                    sleep = sleep,
                    events = state.events,
                    timeline = timeline,
                    currentEventId = current?.id,
                    playingEventId = state.playingEventId,
                    onSelect = { event ->
                        if (!autoZoom) window = window.showing(timeline.clockOf(event.start), timeline.clockOf(event.end))
                        viewModel.seekToEvent(event)
                    },
                    onTogglePlay = viewModel::togglePlayEvent,
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
 * The events of the sleep in order; a tap shows one on the timeline, its button plays it alone.
 * The event at the playhead is highlighted and scrolled into view.
 */
@Composable
private fun EventList(
    sleep: SleepSummary?,
    events: List<SleepEvent>,
    timeline: SleepTimeline,
    currentEventId: Long?,
    playingEventId: Long?,
    onSelect: (SleepEvent) -> Unit,
    onTogglePlay: (SleepEvent) -> Unit,
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

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        items(events, key = { it.id }) { event ->
            EventRow(
                event,
                timeline,
                current = event.id == currentEventId,
                playing = event.id == playingEventId,
                onSelect = onSelect,
                onTogglePlay = onTogglePlay,
            )
        }
        if (sleep != null && events.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(if (sleep.eventsFound) R.string.no_events else R.string.finding_events),
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
    onSelect: (SleepEvent) -> Unit,
    onTogglePlay: (SleepEvent) -> Unit,
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
            .height(64.dp)
            .clickable { onSelect(event) }
            .background(if (current || playing) colors.primary.copy(alpha = 0.1f) else colors.surface)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Box(
            Modifier
                .padding(vertical = 10.dp)
                .width(4.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(kindColor(event.kindColor, colors)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.event_heading, time, formatEventLength(resources, event.end - event.start)),
                style = MaterialTheme.typography.titleSmall.merge(numbers),
            )
            Text(
                stringResource(R.string.event_details, kindName(event), formatPosition(event.start)),
                style = MaterialTheme.typography.bodySmall.merge(numbers),
                color = colors.onSurfaceVariant,
            )
        }
        IconButton(onClick = { onTogglePlay(event) }) {
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (playing) R.string.pause_event else R.string.play_event),
            )
        }
    }
}

/** The name of an event's kind: the user's name, or the app's for the kinds it ships. */
@Composable
private fun kindName(event: SleepEvent): String = event.kindName ?: when (event.kindId) {
    EventKinds.EVENT -> stringResource(R.string.kind_event)
    else -> event.kindId
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
