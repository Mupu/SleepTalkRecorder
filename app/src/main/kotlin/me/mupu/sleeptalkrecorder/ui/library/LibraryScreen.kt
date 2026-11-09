package me.mupu.sleeptalkrecorder.ui.library

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.time.SleepKind
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import me.mupu.sleeptalkrecorder.ui.format.formatClock
import me.mupu.sleeptalkrecorder.ui.format.formatDuration
import me.mupu.sleeptalkrecorder.ui.format.formatPosition
import me.mupu.sleeptalkrecorder.ui.format.groupDate
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import java.time.Duration
import java.time.ZoneId

/** Every sleep, grouped by the day it is named after; the running sleep on top. */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBack: () -> Unit,
    onOpenRunning: () -> Unit,
    onOpenSleep: (Long) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()

    Scaffold(topBar = { AppBar(stringResource(R.string.library), navigationIcon = { BackIcon(onBack) }) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
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
                    val date = SleepDate.of(sleep.startedAt, zone)
                    ListItem(
                        leadingContent = { SleepKindIcon(date.kind) },
                        headlineContent = { Text(sleepTitle(resources, date)) },
                        supportingContent = { Text(sleepSubtitle(context, sleep, zone)) },
                        trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                        modifier = Modifier.clickable { onOpenSleep(sleep.id) },
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
}

@Composable
private fun RunningSleepItem(running: RunningSleep, onClick: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()
    val colors = MaterialTheme.colorScheme

    val since = stringResource(R.string.running_since, formatClock(context, running.sleep.startedAt, zone))
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
            headlineContent = { Text(sleepTitle(resources, SleepDate.of(running.sleep.startedAt, zone))) },
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
internal fun sleepSubtitle(context: Context, sleep: SleepSummary, zone: ZoneId): String {
    val resources = LocalResources.current
    val end = sleep.endedAt ?: return formatClock(context, sleep.startedAt, zone)
    val range = stringResource(
        R.string.time_range,
        formatClock(context, sleep.startedAt, zone),
        formatClock(context, end, zone),
    )
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
