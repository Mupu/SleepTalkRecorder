package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import me.mupu.sleeptalkrecorder.ui.format.formatClock
import me.mupu.sleeptalkrecorder.ui.format.formatPosition
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import me.mupu.sleeptalkrecorder.ui.library.sleepSubtitle
import java.text.NumberFormat
import java.time.Duration
import java.time.ZoneId

/** One sleep: its name and times, a timeline of the whole sleep and the player below it. */
@Composable
fun SleepScreen(viewModel: SleepViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()

    // Playback has no notification or lock-screen controls, so it stops with the screen.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.pause() }

    val sleep = state.sleep
    val title = sleep?.let { sleepTitle(resources, SleepDate.of(it.startedAt, zone)) }.orEmpty()
    Scaffold(topBar = { AppBar(title, navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp)) {
            if (sleep != null) {
                Text(
                    sleepSubtitle(context, sleep, zone),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            state.player?.let { player ->
                Player(
                    player = player,
                    onTogglePlay = viewModel::togglePlay,
                    onScrub = viewModel::scrub,
                    onScrubFinished = viewModel::finishScrub,
                    onNextSpeed = viewModel::nextSpeed,
                )
            }
        }
    }
}

@Composable
private fun Player(
    player: PlayerUiState,
    onTogglePlay: () -> Unit,
    onScrub: (Duration) -> Unit,
    onScrubFinished: () -> Unit,
    onNextSpeed: () -> Unit,
) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val numbers = TextStyle(fontFeatureSettings = "tnum")

    Timeline(player, onScrub, onScrubFinished)

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        FilledIconButton(
            onClick = onTogglePlay,
            enabled = player.hasAudio,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.size(56.dp),
        ) {
            Icon(
                painterResource(if (player.playing) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (player.playing) R.string.pause else R.string.play),
                modifier = Modifier.size(32.dp),
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                player.clock?.let { formatClock(context, it, zone, seconds = true) }.orEmpty(),
                style = MaterialTheme.typography.titleLarge.merge(numbers),
            )
            Text(
                stringResource(R.string.position_short, formatPosition(player.position)),
                style = MaterialTheme.typography.bodyMedium.merge(numbers),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SpeedChip(player.speed, enabled = player.hasAudio, onClick = onNextSpeed)
    }

    val problem = when {
        !player.hasAudio -> R.string.sleep_no_audio
        player.failed -> R.string.playback_failed
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

/** The whole sleep as a slider; dragging shows the target position and seeks when released. */
@Composable
private fun Timeline(player: PlayerUiState, onScrub: (Duration) -> Unit, onScrubFinished: () -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val description = stringResource(R.string.timeline)
    val seconds = player.duration.toMillis() / 1_000f

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Slider(
                value = (player.position.toMillis() / 1_000f).coerceIn(0f, seconds),
                onValueChange = { onScrub(Duration.ofMillis((it * 1_000).toLong())) },
                onValueChangeFinished = onScrubFinished,
                valueRange = 0f..seconds.coerceAtLeast(1f),
                enabled = player.hasAudio,
                modifier = Modifier.semantics { contentDescription = description },
            )
            if (player.hasAudio) {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    TimelineLabel(formatPosition(Duration.ZERO))
                    TimelineLabel(formatPosition(player.duration))
                }
            }
        }
    }
}

@Composable
private fun TimelineLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall.merge(TextStyle(fontFeatureSettings = "tnum")),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
