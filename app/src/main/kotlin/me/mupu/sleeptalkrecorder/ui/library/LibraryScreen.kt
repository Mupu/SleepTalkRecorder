package me.mupu.sleeptalkrecorder.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.time.SleepKind
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.AppBarIcon
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import me.mupu.sleeptalkrecorder.ui.components.SegmentLabel
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatDuration
import me.mupu.sleeptalkrecorder.ui.format.formatEventLength
import me.mupu.sleeptalkrecorder.ui.format.formatPosition
import me.mupu.sleeptalkrecorder.ui.format.groupDate
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import me.mupu.sleeptalkrecorder.ui.sleep.kindName
import java.time.Duration
import java.time.ZoneId

/**
 * Every sleep, grouped by the day it is named after, with the running sleep on top; a long press
 * on a finished sleep deletes it after a confirmation. The second tab lists the favourites of all
 * sleeps under the sleep each belongs to; tapping one plays it in its sleep.
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpenRunning: () -> Unit,
    onOpenSleep: (Long) -> Unit,
    onOpenFavourite: (sleepId: Long, eventId: Long) -> Unit,
    onOpenStats: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showFavourites by rememberSaveable { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val deletionFailedMessage = stringResource(R.string.delete_sleep_failed)
    LaunchedEffect(state.deletionFailed) {
        if (state.deletionFailed) {
            snackbarHostState.showSnackbar(deletionFailedMessage)
            viewModel.deletionFailureShown()
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                stringResource(R.string.library),
                navigationIcon = { BackIcon(onBack) },
                actions = { AppBarIcon(R.drawable.ic_bar_chart, stringResource(R.string.stats), onOpenStats) },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            LibraryTabs(showFavourites, onSelect = { showFavourites = it })
            if (showFavourites) {
                FavouriteList(state, onOpenFavourite)
            } else {
                SleepList(state, onOpenRunning, onOpenSleep, onDelete = viewModel::askToDelete)
            }
        }
    }

    state.deletion?.let { request ->
        DeleteSleepDialog(request, onConfirm = viewModel::confirmDeletion, onDismiss = viewModel::cancelDeletion)
    }
}

/** Sleeps or favourites, as two connected buttons below the top bar. */
@Composable
private fun LibraryTabs(showFavourites: Boolean, onSelect: (Boolean) -> Unit) {
    val tabs = listOf(false to R.string.library_tab_sleeps, true to R.string.library_tab_favourites)
    // Equally high buttons, also when one label takes two lines.
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).height(IntrinsicSize.Min)) {
        tabs.forEachIndexed { index, (favourites, label) ->
            SegmentedButton(
                selected = showFavourites == favourites,
                onClick = { onSelect(favourites) },
                shape = SegmentedButtonDefaults.itemShape(index, tabs.size),
                modifier = Modifier.fillMaxHeight(),
            ) {
                SegmentLabel(stringResource(label))
            }
        }
    }
}

/** Every sleep under the day it is named after, the running sleep on top. */
@Composable
private fun SleepList(state: LibraryUiState, onOpenRunning: () -> Unit, onOpenSleep: (Long) -> Unit, onDelete: (SleepSummary) -> Unit) {
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()

    LazyColumn(Modifier.fillMaxSize()) {
        state.running?.let { running ->
            item(key = "running") { RunningSleepItem(running, onClick = onOpenRunning) }
        }
        for (group in state.groups) {
            item(key = "date-${group.date}") {
                Text(
                    groupDate(resources, group.date),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(group.sleeps, key = { it.id }) { sleep ->
                val date = sleep.date
                ListItem(
                    leadingContent = { SleepKindIcon(date.kind) },
                    headlineContent = { Text(sleepTitle(resources, date)) },
                    supportingContent = {
                        Column {
                            Text(sleepSubtitle(sleep, zone))
                            EventsTag(sleep)
                        }
                    },
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                    modifier = Modifier.combinedClickable(
                        onClick = { onOpenSleep(sleep.id) },
                        onLongClick = { onDelete(sleep) },
                    ),
                )
            }
        }
        if (state.groups.isNotEmpty()) {
            item(key = "hint") {
                Text(
                    stringResource(R.string.library_long_press_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
        if (state.loaded && state.running == null && state.groups.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
    }
}

/**
 * The favourites of all sleeps under the name of their sleep: each by its name, else its words,
 * else its kind, with the clock time it began and its length.
 */
@Composable
private fun FavouriteList(state: LibraryUiState, onOpenFavourite: (sleepId: Long, eventId: Long) -> Unit) {
    val resources = LocalResources.current
    val clock = LocalClockFormatter.current
    val zone = ZoneId.systemDefault()

    LazyColumn(Modifier.fillMaxSize()) {
        for (group in state.favourites) {
            item(key = "favourites-${group.sleep.id}") {
                Text(
                    sleepTitle(resources, group.sleep.date),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(group.favourites, key = { "favourite-${it.event.id}" }) { favourite ->
                val event = favourite.event
                val transcript = event.transcript?.let { stringResource(R.string.transcript_quoted, it) }
                val details = listOfNotNull(
                    favourite.at?.let { clock.format(it, zone, seconds = true) },
                    formatEventLength(resources, event.end - event.start),
                ).joinToString(" · ")
                ListItem(
                    leadingContent = {
                        Icon(painterResource(R.drawable.ic_star), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    },
                    headlineContent = {
                        Text(event.name ?: transcript ?: kindName(event.kindId, event.kindName), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    },
                    supportingContent = {
                        Column {
                            if (event.name != null && transcript != null) Text(transcript, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(details)
                        }
                    },
                    trailingContent = { Icon(painterResource(R.drawable.ic_play), contentDescription = stringResource(R.string.play_event)) },
                    modifier = Modifier.clickable { onOpenFavourite(group.sleep.id, event.id) },
                )
            }
        }
        if (state.loaded && state.favourites.isEmpty()) {
            item(key = "no-favourites") {
                Text(
                    stringResource(R.string.library_no_favourites),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                )
            }
        }
    }
}

/** Asks whether to delete a sleep, naming it and the space its files take up. */
@Composable
private fun DeleteSleepDialog(request: DeletionRequest, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val resources = LocalResources.current
    val name = sleepTitle(resources, request.sleep.date)
    val size = Formatter.formatShortFileSize(LocalContext.current, request.bytes)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(painterResource(R.drawable.ic_delete), contentDescription = null) },
        title = { Text(stringResource(R.string.delete_sleep_title)) },
        text = { Text(stringResource(R.string.delete_sleep_text, name, size)) },
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

@Composable
private fun RunningSleepItem(running: RunningSleep, onClick: () -> Unit) {
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()
    val colors = MaterialTheme.colorScheme

    val since = stringResource(R.string.running_since, LocalClockFormatter.current.format(running.sleep.startedAt, zone))
    val subtitle = running.elapsed?.let { "$since · ${formatPosition(it)}" } ?: since

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(
                containerColor = Color.Transparent,
                headlineColor = colors.onErrorContainer,
                supportingColor = colors.onErrorContainer,
            ),
            leadingContent = {
                CircleIcon(R.drawable.ic_mic, background = colors.error, tint = colors.onError)
            },
            headlineContent = { Text(sleepTitle(resources, running.sleep.date)) },
            supportingContent = { Text(subtitle) },
            trailingContent = {
                Text(
                    stringResource(R.string.live_tag),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onError,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(colors.error)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            },
        )
    }
}

/** How many events a sleep has and how long they last together, or that they are being found. */
@Composable
private fun EventsTag(sleep: SleepSummary) {
    val resources = LocalResources.current
    val count = pluralStringResource(R.plurals.event_count, sleep.eventCount, sleep.eventCount)
    val text = if (!sleep.eventsFound) {
        stringResource(R.string.finding_events)
    } else if (sleep.eventCount == 0) {
        count
    } else {
        stringResource(
            R.string.events_total,
            count,
            formatDuration(resources, Duration.ofMillis(sleep.eventMillis)),
        )
    }
    Text(
        text,
        style = MaterialTheme.typography.labelMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
        color = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** The round icon in front of a sleep: a moon for a night, a sun for a nap. */
@Composable
internal fun SleepKindIcon(kind: SleepKind) {
    val colors = MaterialTheme.colorScheme
    when (kind) {
        SleepKind.NIGHT -> CircleIcon(R.drawable.ic_bedtime, colors.primaryContainer, colors.onPrimaryContainer)
        SleepKind.NAP -> CircleIcon(R.drawable.ic_light_mode, colors.tertiaryContainer, colors.onTertiaryContainer)
    }
}

@Composable
private fun CircleIcon(icon: Int, background: Color, tint: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(40.dp).clip(CircleShape).background(background),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint)
    }
}

/** The second line of a finished sleep: "23:41–07:12 · 7 h 31 min", plus its recordings if several. */
@Composable
internal fun sleepSubtitle(sleep: SleepSummary, zone: ZoneId): String {
    val resources = LocalResources.current
    val clock = LocalClockFormatter.current
    val end = sleep.endedAt ?: return clock.format(sleep.startedAt, zone)
    val range = stringResource(R.string.time_range, clock.format(sleep.startedAt, zone), clock.format(end, zone))
    val duration = formatDuration(resources, Duration.between(sleep.startedAt, end))
    val parts = listOfNotNull(
        range,
        duration,
        if (sleep.recordingCount > 1) {
            pluralStringResource(R.plurals.recording_count, sleep.recordingCount, sleep.recordingCount)
        } else {
            null
        },
    )
    return parts.joinToString(" · ")
}
