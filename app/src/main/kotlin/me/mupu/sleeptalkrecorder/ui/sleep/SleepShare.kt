package me.mupu.sleeptalkrecorder.ui.sleep

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.share.EventShares
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import java.time.Duration

/**
 * [ShareSheet] for the open share [step] of [viewModel]'s [sleep], whose cards and caption name the
 * events as [shareLabels] words them.
 */
@Composable
fun SleepShareSheet(
    viewModel: SleepViewModel,
    step: ShareUiState,
    sleep: SleepSummary?,
    timeline: SleepTimeline,
    loudness: SleepLoudness?,
    position: () -> Duration,
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val clock = LocalClockFormatter.current
    val labels = remember(step.events, sleep, clock, resources) {
        step.events.map { shareLabels(resources, clock, timeline, sleep, it) }
    }
    ShareSheet(
        state = step,
        labels = labels,
        timeline = timeline,
        loudness = loudness,
        position = position,
        onSelect = viewModel::selectShare,
        onMargins = viewModel::setShareMargins,
        onReset = viewModel::resetShare,
        onFormat = viewModel::setShareFormat,
        onCaptionItem = viewModel::setCaptionItem,
        onListen = viewModel::listenToShare,
        onShare = { viewModel.share(labels, cardColors(context)) },
        onDismiss = viewModel::closeShare,
    )
}

/** Hands the written [files] to Android's share sheet. */
fun startShareChooser(context: Context, files: SharedFiles) {
    val resources = context.resources
    context.startActivity(EventShares.chooser(files.uris, files.format, files.caption, resources.getString(R.string.share_chooser)))
}
