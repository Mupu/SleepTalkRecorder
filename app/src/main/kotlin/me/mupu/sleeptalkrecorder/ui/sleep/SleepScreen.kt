package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.EventWay
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.Sorting
import me.mupu.sleeptalkrecorder.detection.FoundBy
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.data.TagEntity
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.playback.PlaybackKind
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.transcription.ModelChange
import me.mupu.sleeptalkrecorder.transcription.TranscriptionStatus
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.AppBarIcon
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import me.mupu.sleeptalkrecorder.ui.components.SegmentLabel
import me.mupu.sleeptalkrecorder.ui.components.SelectionBar
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatDuration
import me.mupu.sleeptalkrecorder.ui.format.formatEventLength
import me.mupu.sleeptalkrecorder.ui.format.formatPosition
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import me.mupu.sleeptalkrecorder.ui.library.sleepSubtitle
import me.mupu.sleeptalkrecorder.ui.share.EventShareSheet
import me.mupu.sleeptalkrecorder.ui.share.startShareChooser
import me.mupu.sleeptalkrecorder.ui.share.totalLength
import me.mupu.sleeptalkrecorder.ui.tags.EventTagLabels
import me.mupu.sleeptalkrecorder.ui.tags.TagDialogs
import me.mupu.sleeptalkrecorder.ui.tags.TagFilter
import me.mupu.sleeptalkrecorder.ui.tags.TagFilterBar
import java.text.NumberFormat
import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.launch

/**
 * One sleep: its name and times, the zoomable loudness timeline with its overview, the player with
 * full and cut playback, and the list of its events, each of which plays on its own, filtered by
 * tags chosen in the filter window ([TagDialogs]) and shown as pills above it.
 *
 * The list highlights the event at the playhead and scrolls to it. Auto zoom, on until the user
 * pans or zooms the timeline by hand, frames that event and keeps the playhead in view.
 *
 * The sensitivity of the sleep is set from the app bar's menu, that around an event from the
 * event's menu, in [SensitivitySheet]. The event's menu also hides it, which a snackbar undoes; a
 * hidden event has an Unhide button on its row, and the timeline draws it grey like silence. A speech event's text can be
 * corrected, or the event marked as not speech, which a snackbar undoes; when the active model is
 * another than the one that made the sleep's transcripts, a hint offers to recognise them again.
 * An event can be recognised again on its own with another installed model, whose result its open
 * transcript then shows beside the others; with only one model installed that leads to the
 * settings through [onOpenModels].
 * Sharing an event opens [ShareSheet], and the audio file it writes goes to Android's share sheet.
 */
@Composable
fun SleepScreen(viewModel: SleepViewModel, onBack: () -> Unit, onOpenStats: () -> Unit, onOpenModels: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val sensitivity by viewModel.sensitivity.collectAsStateWithLifecycle()
    val transcription by viewModel.transcription.collectAsStateWithLifecycle()
    val filterKinds by viewModel.filterKinds.collectAsStateWithLifecycle()
    val modelChange by viewModel.modelChange.collectAsStateWithLifecycle()
    val share by viewModel.sharing.state.collectAsStateWithLifecycle()
    val installedModels by viewModel.installedModels.collectAsStateWithLifecycle()
    val recognising by viewModel.recognising.collectAsStateWithLifecycle()
    val tags by viewModel.tagging.tags.collectAsStateWithLifecycle()
    // Read only where it is drawn or shown, so the moving playhead recomposes nothing else.
    val positionState = viewModel.position.collectAsStateWithLifecycle()
    val position = remember(positionState) { { positionState.value } }
    val resources = LocalResources.current
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val snackbars = remember { SnackbarHostState() }

    // Playback has no notification or lock-screen controls, so it stops with the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }

    LaunchedEffect(viewModel) {
        viewModel.eventsHidden.collect { events ->
            val result = snackbars.showSnackbar(
                resources.getQuantityString(R.plurals.events_hidden, events.size, events.size),
                actionLabel = resources.getString(R.string.undo),
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.unhide(events)
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
        viewModel.sharing.shared.collect { files ->
            if (files == null) {
                snackbars.showSnackbar(resources.getString(R.string.share_failed))
            } else {
                startShareChooser(context, files)
            }
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.recognitionFailed.collect { snackbars.showSnackbar(resources.getString(R.string.recognise_again_failed)) }
    }
    LaunchedEffect(viewModel) {
        viewModel.sensitivityApplied.collect { applied ->
            val message = if (applied.wholeSleep) R.plurals.sensitivity_applied_sleep else R.plurals.sensitivity_applied_event
            snackbars.showSnackbar(resources.getQuantityString(message, applied.events, applied.events))
        }
    }

    // Back ends selection mode before it leaves the screen.
    BackHandler(enabled = state.selected.isNotEmpty(), onBack = viewModel::clearSelection)

    var choosingWay by rememberSaveable { mutableStateOf(false) }
    val searchAgainStarted = stringResource(R.string.search_again_started)
    val scope = rememberCoroutineScope()

    val sleep = state.sleep
    val title = sleep?.let { sleepTitle(resources, it.date) }.orEmpty()
    Scaffold(
        topBar = {
            if (state.selected.isNotEmpty()) {
                EventSelectionBar(
                    state.selected,
                    onClose = viewModel::clearSelection,
                    onShare = viewModel::shareSelected,
                    onTag = viewModel::tagSelected,
                    onHide = viewModel::hideSelected,
                )
            } else {
                SleepAppBar(
                    title, state, transcription, modelChange, viewModel, onBack, onOpenStats,
                    onChooseWay = { choosingWay = true },
                    onSearchAgain = {
                        viewModel.searchAgain()
                        scope.launch { snackbars.showSnackbar(searchAgainStarted) }
                    },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbars) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (sleep != null) {
                Text(
                    listOfNotNull(sleepSubtitle(sleep, zone), eventSummary(sleep, state.wayTotals)).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            state.newWay?.takeIf { sleep?.isRunning == false && (!it.searched || it.progress != null || it.partial) }?.let { newWay ->
                NewWayHint(newWay, onOpenModels, Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, end = 8.dp))
            }
            if (choosingWay) {
                WayDialog(state.way, onChoose = viewModel::showWay, onDismiss = { choosingWay = false })
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
                var choosingModel by remember { mutableStateOf<SleepEvent?>(null) }
                var expanded by remember { mutableStateOf(emptySet<Long>()) }
                var naming by remember { mutableStateOf<SleepEvent?>(null) }
                var filtering by rememberSaveable { mutableStateOf(false) }

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
                        canSkipBack = state.previousEvent != null,
                        canSkipForward = state.nextEvent != null,
                        onSkip = viewModel::skipEvent,
                        onNextSpeed = viewModel::nextSpeed,
                        onCutChange = viewModel::setCut,
                    )
                }
                HorizontalDivider()
                if (state.events.isNotEmpty()) {
                    TagFilterBar(
                        state.filter, tags, filterKinds,
                        onOpen = { filtering = true },
                        onRemove = viewModel::toggleFilterTag,
                        onClear = viewModel::clearFilter,
                        extraTags = state.extraTags,
                    )
                }
                if (state.listedEvents.isNotEmpty()) ListedSummary(state.listedEvents)
                EventList(
                    sleep = sleep,
                    events = state.listedEvents,
                    filter = state.filter,
                    timeline = timeline,
                    currentEventId = current?.id,
                    playingEventId = state.playingEventId,
                    selectedIds = state.selected.mapTo(HashSet()) { it.id },
                    onToggleSelection = viewModel::toggleSelection,
                    onSelect = { event ->
                        if (!autoZoom) window = window.showing(timeline.clockOf(event.start), timeline.clockOf(event.end))
                        viewModel.seekToEvent(event)
                    },
                    onTogglePlay = viewModel::togglePlayEvent,
                    onHide = viewModel::setHidden,
                    tags = tags,
                    onTags = viewModel::openTags,
                    onName = { naming = it },
                    onShare = viewModel::openShare,
                    onSensitivity = if (state.sensitivityAvailable) viewModel::openSensitivity else null,
                    modelChange = modelChange,
                    onCorrect = { correcting = it },
                    onMarkSpeech = viewModel::markSpeech,
                    onRerun = viewModel::runSpeechRecognition,
                    expanded = expanded,
                    onToggleTranscript = { id -> expanded = if (id in expanded) expanded - id else expanded + id },
                    recognising = recognising,
                    // Only the first way's events are recognised again on their own.
                    onRecogniseAgain = if (transcription == TranscriptionStatus.Off || state.way != EventWay.OLD) null else { event -> choosingModel = event },
                    canRecogniseAgain = transcription.canRecogniseAgain,
                    developerMode = state.developerMode,
                )
                naming?.let { event ->
                    NameEventDialog(
                        event,
                        onSave = { name -> viewModel.setName(event, name) },
                        onDismiss = { naming = null },
                    )
                }
                correcting?.let { event ->
                    CorrectTranscriptDialog(
                        event,
                        onSave = { text, deleted -> viewModel.correctTranscript(event, text, deleted) },
                        onDismiss = { correcting = null },
                    )
                }
                choosingModel?.let { event ->
                    RecogniseAgainDialog(
                        installedModels,
                        onRecognise = { model ->
                            // Opened, so the new result is seen beside the others once it is there.
                            expanded = expanded + event.id
                            viewModel.recogniseAgain(event, model)
                        },
                        onOpenSettings = onOpenModels,
                        onDismiss = { choosingModel = null },
                    )
                }

                TagDialogs(
                    viewModel.tagging,
                    filterKinds,
                    state.filter,
                    filterOpen = filtering,
                    count = { tag -> state.events.count(TagFilter(setOf(tag))::shows) },
                    onToggleFilter = viewModel::toggleFilterTag,
                    onClearFilter = viewModel::clearFilter,
                    onCloseFilter = { filtering = false },
                    onApplied = viewModel::clearSelection,
                    extraTags = state.extraTags,
                )
                share?.let { step -> EventShareSheet(viewModel.sharing, step, state.loudness, position) }
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

/** The sleep screen's app bar outside selection mode: back, speech recognition, stats and the sleep's menu. */
@Composable
private fun SleepAppBar(
    title: String,
    state: SleepUiState,
    transcription: TranscriptionStatus,
    modelChange: ModelChange?,
    viewModel: SleepViewModel,
    onBack: () -> Unit,
    onOpenStats: () -> Unit,
    onChooseWay: () -> Unit,
    onSearchAgain: () -> Unit,
) {
    AppBar(
        title,
        navigationIcon = { BackIcon(onBack) },
        actions = {
            SpeechRecognitionStatus(
                transcription,
                state.events,
                modelChange,
                onRun = viewModel::runSpeechRecognition,
                onPause = viewModel::pauseSpeechRecognition,
                onContinue = viewModel::continueSpeechRecognition,
            )
            AppBarIcon(R.drawable.ic_bar_chart, stringResource(R.string.stats_of_sleep), onOpenStats)
            MoreMenu(
                listOfNotNull(
                    MenuEntry(R.string.sensitivity_sleep, R.drawable.ic_tune) { viewModel.openSensitivity(null) }
                        .takeIf { state.sensitivityAvailable },
                    when (transcription) {
                        TranscriptionStatus.Off -> null
                        is TranscriptionStatus.Running ->
                            MenuEntry(R.string.pause_speech_recognition, R.drawable.ic_pause, onClick = viewModel::pauseSpeechRecognition)
                        is TranscriptionStatus.Paused ->
                            MenuEntry(R.string.continue_speech_recognition, R.drawable.ic_play, onClick = viewModel::continueSpeechRecognition)
                        else -> MenuEntry(
                            if (transcription.nothingRecognised) R.string.run_speech_recognition else R.string.run_speech_recognition_again,
                            R.drawable.ic_subtitles,
                            enabled = transcription.canRun,
                            onClick = viewModel::runSpeechRecognition,
                        )
                    },
                    MenuEntry(R.string.way_menu, R.drawable.ic_filter_list, onClick = onChooseWay),
                    MenuEntry(R.string.search_again, R.drawable.ic_center_focus_strong, enabled = transcription !is TranscriptionStatus.Running, onClick = onSearchAgain)
                        .takeIf { state.developerMode && state.sleep?.isRunning == false && transcription != TranscriptionStatus.Off },
                ),
            )
        },
    )
}

/**
 * The app bar of selection mode, with buttons that end the selection, share the events, open the
 * tag window for them or hide them.
 */
@Composable
private fun EventSelectionBar(selected: List<SleepEvent>, onClose: () -> Unit, onShare: () -> Unit, onTag: () -> Unit, onHide: () -> Unit) {
    SelectionBar(selected.size, totalLength(selected), onClose) {
        AppBarIcon(R.drawable.ic_share, stringResource(R.string.share_event), onShare)
        AppBarIcon(R.drawable.ic_label_outline, stringResource(R.string.tags_button), onTag)
        IconButton(onClick = onHide, enabled = selected.any { !it.hidden && !it.sharesMarks }) {
            Icon(painterResource(R.drawable.ic_visibility_off), contentDescription = stringResource(R.string.hide_event))
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

/**
 * How many events the sleep has that are not hidden and how long they last together, whatever the
 * filter lists, as in the library: "12 events · 3 min"; that they are still being found, or null
 * while the sleep is recorded. With [wayTotals], those of the way shown, else the first way's.
 */
@Composable
private fun eventSummary(sleep: SleepSummary, wayTotals: Pair<Int, Duration>?): String? {
    val events = wayTotals?.first ?: sleep.eventCount
    val length = wayTotals?.second ?: Duration.ofMillis(sleep.eventMillis)
    val count = pluralStringResource(R.plurals.event_count, events, events)
    return when {
        sleep.isRunning -> null
        !sleep.eventsFound -> stringResource(R.string.finding_events)
        events == 0 -> count
        else -> stringResource(R.string.events_total, count, formatDuration(LocalResources.current, length))
    }
}

/**
 * How many events the list shows with its filter and how long they last together, without lead-in
 * and tail: "In the list: 42 events · 3 min 10 s".
 */
@Composable
private fun ListedSummary(events: List<SleepEvent>) {
    val resources = LocalResources.current
    val length = remember(events) { totalLength(events) }
    Text(
        stringResource(
            R.string.events_listed,
            pluralStringResource(R.plurals.event_count, events.size, events.size),
            formatEventLength(resources, length),
        ),
        style = MaterialTheme.typography.labelMedium.merge(Numbers),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun Player(
    player: PlayerUiState,
    timeline: SleepTimeline,
    position: () -> Duration,
    onTogglePlay: () -> Unit,
    canSkipBack: Boolean,
    canSkipForward: Boolean,
    onSkip: (forward: Boolean) -> Unit,
    onNextSpeed: () -> Unit,
    onCutChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        IconButton(onClick = { onSkip(false) }, enabled = player.hasAudio && canSkipBack) {
            Icon(painterResource(R.drawable.ic_skip_previous), contentDescription = stringResource(R.string.previous_event))
        }
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
        IconButton(onClick = { onSkip(true) }, enabled = player.hasAudio && canSkipForward) {
            Icon(painterResource(R.drawable.ic_skip_next), contentDescription = stringResource(R.string.next_event))
        }
        Spacer(Modifier.width(12.dp))
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
 * The events the [filter] shows, in order, each with its tags; a tap shows one on the timeline, its
 * button plays it alone, its tag button opens its tag window, a hidden one has a button that
 * unhides it, and its menu names it, shares it, hides it, opens the sensitivity around it while
 * [onSensitivity] is given, and for a speech event corrects its text or marks it as not speech. The event at the
 * playhead is highlighted and scrolled into view.
 *
 * A long press selects an event, which starts selection mode: while any is selected, a tap selects
 * an event or takes it out of the selection, and each row shows a checkbox instead of its buttons.
 */
@Composable
private fun EventList(
    sleep: SleepSummary?,
    events: List<SleepEvent>,
    filter: TagFilter,
    timeline: SleepTimeline,
    currentEventId: Long?,
    playingEventId: Long?,
    selectedIds: Set<Long>,
    onToggleSelection: (SleepEvent) -> Unit,
    onSelect: (SleepEvent) -> Unit,
    onTogglePlay: (SleepEvent) -> Unit,
    onHide: (SleepEvent, Boolean) -> Unit,
    tags: List<TagEntity>,
    onTags: (SleepEvent) -> Unit,
    onName: (SleepEvent) -> Unit,
    onShare: (SleepEvent) -> Unit,
    onSensitivity: ((SleepEvent) -> Unit)?,
    modelChange: ModelChange?,
    onCorrect: (SleepEvent) -> Unit,
    onMarkSpeech: (SleepEvent, Boolean) -> Unit,
    onRerun: () -> Unit,
    expanded: Set<Long>,
    onToggleTranscript: (Long) -> Unit,
    recognising: Map<Stretch, TranscriptionModel>,
    onRecogniseAgain: ((SleepEvent) -> Unit)?,
    canRecogniseAgain: Boolean,
    developerMode: Boolean,
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
                selection = when {
                    selectedIds.isEmpty() -> null
                    else -> event.id in selectedIds
                },
                onToggleSelection = onToggleSelection,
                transcriptExpanded = event.id in expanded,
                onToggleTranscript = { onToggleTranscript(event.id) },
                onSelect = onSelect,
                onTogglePlay = onTogglePlay,
                onHide = onHide,
                tags = tags,
                onTags = onTags,
                onName = onName,
                onShare = onShare,
                onSensitivity = onSensitivity,
                modelChange = modelChange,
                onCorrect = onCorrect,
                onMarkSpeech = onMarkSpeech,
                onRerun = onRerun,
                recognisingWith = recognising[event.stretch],
                onRecogniseAgain = onRecogniseAgain,
                canRecogniseAgain = canRecogniseAgain,
                developerMode = developerMode,
            )
        }
        if (sleep != null && events.isEmpty()) {
            item(key = "empty") {
                val empty = when {
                    !sleep.eventsFound -> R.string.finding_events
                    filter.chosen.isNotEmpty() -> R.string.no_events_filter
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
    /** Whether the event is selected while selection mode lasts; null outside it. */
    selection: Boolean?,
    onToggleSelection: (SleepEvent) -> Unit,
    transcriptExpanded: Boolean,
    onToggleTranscript: () -> Unit,
    onSelect: (SleepEvent) -> Unit,
    onTogglePlay: (SleepEvent) -> Unit,
    onHide: (SleepEvent, Boolean) -> Unit,
    tags: List<TagEntity>,
    onTags: (SleepEvent) -> Unit,
    onName: (SleepEvent) -> Unit,
    onShare: (SleepEvent) -> Unit,
    onSensitivity: ((SleepEvent) -> Unit)?,
    modelChange: ModelChange?,
    onCorrect: (SleepEvent) -> Unit,
    onMarkSpeech: (SleepEvent, Boolean) -> Unit,
    onRerun: () -> Unit,
    recognisingWith: TranscriptionModel?,
    onRecogniseAgain: ((SleepEvent) -> Unit)?,
    canRecogniseAgain: Boolean,
    developerMode: Boolean,
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
            .combinedClickable(
                onLongClick = { onToggleSelection(event) },
                onClick = { if (selection != null) onToggleSelection(event) else onSelect(event) },
            )
            .background(
                when {
                    selection == true -> colors.secondaryContainer
                    current || playing -> colors.primary.copy(alpha = 0.1f)
                    else -> colors.surface
                },
            )
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
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Column(Modifier.alpha(if (event.hidden) 0.6f else 1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.event_heading, time, formatEventLength(resources, event.end - event.start)),
                        style = MaterialTheme.typography.titleSmall.merge(Numbers),
                    )
                    if (event.hidden) HiddenTag()
                }
                EventTagLabels(event, tags)
                event.name?.let { name ->
                    Text(name, style = MaterialTheme.typography.labelLarge, color = colors.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    stringResource(R.string.event_details, kindName(event.kindId, event.kindName), formatPosition(event.start)),
                    style = MaterialTheme.typography.bodySmall.merge(Numbers),
                    color = colors.onSurfaceVariant,
                )
                if (developerMode) event.sorting?.let { SortingInfo(it) }
                TranscriptLine(
                    event,
                    transcriptExpanded,
                    onToggleTranscript,
                    modelChange,
                    onRerun = onRerun,
                )
                recognisingWith?.let { RecognisingLine(it) }
            }
            // Below the text rather than beside the buttons, which leave the text no room for it.
            if (event.hidden && selection == null) {
                // Moved left by its padding, so its icon lines up with the text above.
                TextButton(
                    onClick = { onHide(event, false) },
                    enabled = !event.sharesMarks,
                    contentPadding = ButtonDefaults.TextButtonWithIconContentPadding,
                    modifier = Modifier.offset(x = -ButtonDefaults.TextButtonWithIconContentPadding.calculateLeftPadding(LayoutDirection.Ltr)),
                ) {
                    Icon(painterResource(R.drawable.ic_visibility), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.unhide_event))
                }
            }
        }
        if (selection != null) {
            Checkbox(checked = selection, onCheckedChange = { onToggleSelection(event) }, modifier = Modifier.padding(end = 4.dp))
            return@Row
        }
        IconButton(onClick = { onTogglePlay(event) }) {
            Icon(
                painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (playing) R.string.pause_event else R.string.play_event),
            )
        }
        // Filled in the primary colour while the event has tags of the user, outlined otherwise.
        IconButton(onClick = { onTags(event) }) {
            Icon(
                painterResource(if (event.tagIds.isEmpty()) R.drawable.ic_label_outline else R.drawable.ic_label),
                contentDescription = stringResource(R.string.tags_button),
                tint = if (event.tagIds.isEmpty()) LocalContentColor.current else colors.primary,
            )
        }
        MoreMenu(
            listOfNotNull(
                MenuEntry(R.string.name_event_menu, R.drawable.ic_title, enabled = !event.sharesMarks) { onName(event) },
                MenuEntry(R.string.share_event, R.drawable.ic_share) { onShare(event) },
                MenuEntry(R.string.hide_event, R.drawable.ic_visibility_off, enabled = !event.sharesMarks) { onHide(event, true) }.takeUnless { event.hidden },
                onSensitivity?.let { MenuEntry(R.string.sensitivity_here, R.drawable.ic_tune) { it(event) } },
                onRecogniseAgain?.let {
                    MenuEntry(R.string.recognise_again, R.drawable.ic_subtitles, enabled = canRecogniseAgain && recognisingWith == null) { it(event) }
                },
                MenuEntry(R.string.correct_text, R.drawable.ic_edit, enabled = !event.sharesMarks) { onCorrect(event) }.takeIf { event.isVoice },
                MenuEntry(R.string.not_speech_noise, R.drawable.ic_voice_over_off, enabled = !event.sharesMarks) { onMarkSpeech(event, false) }
                    .takeIf { event.isVoice },
                MenuEntry(R.string.mark_speech, R.drawable.ic_record_voice, enabled = !event.sharesMarks) { onMarkSpeech(event, true) }
                    .takeUnless { event.isSpeech },
                // Why the entries above are greyed out, and where to make these edits instead.
                MenuEntry(R.string.edit_in_old_view, R.drawable.ic_warning, enabled = false) {}.takeIf { event.sharesMarks },
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

/** Developer mode: what the second way knows about an event, "Found by both · human 0.42 · rise 12.5 dB". */
@Composable
private fun SortingInfo(sorting: Sorting) {
    val found = stringResource(
        when (sorting.foundBy) {
            FoundBy.LOUDNESS -> R.string.found_by_loudness
            FoundBy.LISTENING -> R.string.found_by_listening
            FoundBy.BOTH -> R.string.found_by_both
        },
    )
    val locale = LocalResources.current.configuration.locales[0]
    Text(
        stringResource(R.string.event_info, found, "%.2f".format(locale, sorting.score), "%.1f".format(locale, sorting.riseDb)),
        style = MaterialTheme.typography.bodySmall.merge(Numbers),
        color = MaterialTheme.colorScheme.tertiary,
    )
}

/**
 * Where the second way of finding events stands while its events are shown but it has not
 * searched the sleep: it runs with speech recognition, or it waits for its models, which the
 * button downloads in the settings.
 */
@Composable
private fun NewWayHint(state: NewWayState, onOpenModels: () -> Unit, modifier: Modifier = Modifier) {
    val progress = state.progress
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            when {
                progress?.searching == true -> stringResource(R.string.transcription_searching, progress.done)
                progress != null -> stringResource(R.string.new_way_recognising, (progress.done + 1).coerceAtMost(progress.total), progress.total)
                state.modelsMissing && !state.searched -> stringResource(R.string.new_way_no_models)
                state.partial -> stringResource(R.string.new_way_partial)
                else -> stringResource(R.string.new_way_not_searched)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (state.modelsMissing && !state.searched && progress == null) {
            TextButton(onClick = onOpenModels) { Text(stringResource(R.string.new_way_get_models)) }
        }
    }
}

/** Chooses which way's events the sleep screen shows, for every sleep. */
@Composable
private fun WayDialog(selected: EventWay, onChoose: (EventWay) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.way_title)) },
        text = {
            Column(Modifier.selectableGroup()) {
                for (way in EventWay.entries) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(way == selected, role = Role.RadioButton) {
                                onChoose(way)
                                onDismiss()
                            }
                            .padding(vertical = 8.dp),
                    ) {
                        RadioButton(selected = way == selected, onClick = null)
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(stringResource(way.label), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(way.hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.way_remembered),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private val EventWay.label: Int
    get() = when (this) {
        EventWay.OLD -> R.string.way_old
        EventWay.NEW -> R.string.way_new
        EventWay.COMPARISON -> R.string.way_comparison
    }

private val EventWay.hint: Int
    get() = when (this) {
        EventWay.OLD -> R.string.way_old_hint
        EventWay.NEW -> R.string.way_new_hint
        EventWay.COMPARISON -> R.string.way_comparison_hint
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
