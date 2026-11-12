package me.mupu.sleeptalkrecorder.ui.home

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.readiness.Requirement
import me.mupu.sleeptalkrecorder.recording.RecorderProblem
import me.mupu.sleeptalkrecorder.recording.RecorderState
import me.mupu.sleeptalkrecorder.time.RecordingClock
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.AppBarIcon
import me.mupu.sleeptalkrecorder.ui.components.texts
import me.mupu.sleeptalkrecorder.ui.format.formatDuration
import me.mupu.sleeptalkrecorder.ui.format.formatPosition
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import me.mupu.sleeptalkrecorder.ui.library.SleepKindIcon
import me.mupu.sleeptalkrecorder.ui.library.sleepSubtitle
import java.time.Duration
import java.time.ZoneId
import kotlin.math.roundToInt

/** The start screen: the big record button, the elapsed time and a live level meter. */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenLibrary: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSleep: (Long) -> Unit,
    onFixReadiness: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Asks for the microphone (and, for the Stop button in the notification, notifications)
    // on the first tap; the recorder itself reports when the microphone was refused.
    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.startRecording() }
    val onRecord = {
        val wanted = listOf(Manifest.permission.RECORD_AUDIO) +
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        val missing = wanted
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (Manifest.permission.RECORD_AUDIO in missing) {
            requestPermissions.launch(missing.toTypedArray())
        } else {
            viewModel.startRecording()
        }
    }

    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(R.string.app_name),
                navigationIcon = { AppBarIcon(R.drawable.ic_library, stringResource(R.string.library), onOpenLibrary) },
                actions = { AppBarIcon(R.drawable.ic_settings, stringResource(R.string.settings), onOpenSettings) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.missing.isNotEmpty()) {
                ReadinessBanner(state.missing, onFix = onFixReadiness)
            }
            // Nothing about updates distracts while a sleep is recorded; an update never installs then.
            if (state.recorder !is RecorderState.Recording && state.recorder != RecorderState.Starting) {
                UpdateCard(state.update, onDownload = viewModel::downloadUpdate, onInstall = viewModel::installUpdate)
            }
            RecorderPanel(
                state = state,
                onRecord = onRecord,
                onStop = viewModel::stopRecording,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            val lastSleep = state.lastSleep
            if (lastSleep != null && state.recorder !is RecorderState.Recording) {
                LastSleepCard(lastSleep, onClick = { onOpenSleep(lastSleep.id) })
            }
        }
    }
}

@Composable
private fun RecorderPanel(
    state: HomeUiState,
    onRecord: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val recorder = state.recorder
    val progress = (recorder as? RecorderState.Recording)?.progress
    val recording = recorder is RecorderState.Recording || recorder == RecorderState.Starting
    val resources = LocalResources.current

    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress != null) {
                LiveTag(stringResource(R.string.live))
                Text(
                    sleepTitle(resources, progress.sleepDate),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(if (recording) R.string.starting else R.string.ready),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val elapsed = progress?.let { RecordingClock(it.startedAt, it.sampleRate).durationOf(it.frames) } ?: Duration.ZERO
        Text(
            formatPosition(elapsed),
            // Tabular digits keep the time from jittering as it counts.
            style = MaterialTheme.typography.displayMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
        )

        RecordButton(recording = recording, onClick = if (recording) onStop else onRecord)

        LevelMeter(levels = state.levels, active = progress != null)
        Text(
            if (progress != null) {
                stringResource(R.string.level_dbfs, progress.levelDbfs.roundToInt())
            } else {
                stringResource(R.string.meter_idle)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (progress != null) {
            state.night?.let { night ->
                val found = night.found
                Text(
                    if (found == null) {
                        stringResource(R.string.night_scan_waiting)
                    } else {
                        stringResource(
                            R.string.night_scan_found,
                            pluralStringResource(R.plurals.event_count, found.events, found.events),
                            formatDuration(resources, found.searchedTo),
                        )
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        if (recorder is RecorderState.Failed) {
            Text(
                stringResource(
                    when (recorder.reason) {
                        RecorderProblem.NOT_ALLOWED -> R.string.problem_not_allowed
                        RecorderProblem.MICROPHONE_UNAVAILABLE -> R.string.problem_microphone
                        RecorderProblem.STORAGE_FAILED -> R.string.problem_storage
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The round record/stop button; it pulses while a sleep is recorded. */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val container by animateColorAsState(if (recording) colors.error else colors.primaryContainer, label = "container")
    val content by animateColorAsState(if (recording) colors.onError else colors.onPrimaryContainer, label = "content")

    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(212.dp)) {
        if (recording) {
            val pulse = rememberInfiniteTransition(label = "pulse")
            val scale = pulse.animateFloat(
                initialValue = 0.95f,
                targetValue = 1.12f,
                animationSpec = infiniteRepeatable(tween(2_000), RepeatMode.Restart),
                label = "scale",
            )
            val alpha = pulse.animateFloat(
                initialValue = 0.5f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(tween(2_000), RepeatMode.Restart),
                label = "alpha",
            )
            // The animated values are read in the layer, so the pulse only redraws the ring and
            // never recomposes the screen, for as long as the recording runs.
            Box(
                Modifier
                    .size(196.dp)
                    .graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        this.alpha = alpha.value
                    }
                    .border(3.dp, colors.error, CircleShape),
            )
        }
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = container,
            contentColor = content,
            shadowElevation = 4.dp,
            modifier = Modifier.size(188.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    painterResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic),
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    stringResource(if (recording) R.string.stop else R.string.record),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

/** Bars of the most recent levels, the newest on the right. */
@Composable
private fun LevelMeter(levels: List<Double>, active: Boolean) {
    val color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val padded = List(HomeViewModel.METER_BARS - levels.size) { METER_BOTTOM_DBFS } + levels

    Row(
        modifier = Modifier.height(48.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        for (level in padded) {
            Box(
                Modifier
                    .width(6.dp)
                    .height((4 + 44 * meterFraction(level)).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}

// A quiet bedroom sits around -60 dBFS and speech close to the phone around -25 dBFS, so the
// meter spans -70 to -10 dBFS.
private const val METER_BOTTOM_DBFS = -70.0
private const val METER_TOP_DBFS = -10.0

private fun meterFraction(dbfs: Double): Float =
    ((dbfs - METER_BOTTOM_DBFS) / (METER_TOP_DBFS - METER_BOTTOM_DBFS)).coerceIn(0.0, 1.0).toFloat()

@Composable
private fun LiveTag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun LastSleepCard(sleep: SleepSummary, onClick: () -> Unit) {
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()
    val date = sleep.date

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 16.dp),
    ) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { SleepKindIcon(date.kind) },
            overlineContent = { Text(stringResource(R.string.last_sleep)) },
            headlineContent = { Text(sleepTitle(resources, date)) },
            supportingContent = { Text(sleepSubtitle(sleep, zone)) },
            trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
        )
    }
}

/** The warning that something a whole night needs is missing, with the way to fix it. */
@Composable
private fun ReadinessBanner(missing: Set<Requirement>, onFix: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val names = missing.sortedBy { it.ordinal }.map { stringResource(it.texts.title) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = colors.errorContainer,
        contentColor = colors.onErrorContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(painterResource(R.drawable.ic_warning), contentDescription = null)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.readiness_banner_title), style = MaterialTheme.typography.labelLarge)
                Text(names.joinToString(), style = MaterialTheme.typography.bodySmall)
                Button(
                    onClick = onFix,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.error, contentColor = colors.onError),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.fix))
                }
            }
        }
    }
}
