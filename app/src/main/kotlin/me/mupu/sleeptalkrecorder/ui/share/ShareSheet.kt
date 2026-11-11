package me.mupu.sleeptalkrecorder.ui.share

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.share.CaptionItem
import me.mupu.sleeptalkrecorder.share.EventLabels
import me.mupu.sleeptalkrecorder.share.ShareFormat
import me.mupu.sleeptalkrecorder.share.ShareStretch
import me.mupu.sleeptalkrecorder.share.ShareStretches
import me.mupu.sleeptalkrecorder.share.caption
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatEventLength
import me.mupu.sleeptalkrecorder.ui.sleep.SleepLoudness
import java.time.Duration
import kotlin.math.roundToLong

/**
 * The step before events are shared. For a single event it shows the loudness around it with the
 * stretch that is shared, a range slider that moves the stretch's ends and, where a player can,
 * a button that listens to it; it opens with the event as cut playback plays it, with the settings' lead-in and tail.
 * For several events, each shared as a file of its own and each perhaps of another sleep, one
 * lead-in and one tail apply to all.
 * Below follow the format with the items the caption names, and the share button.
 *
 * Like the sensitivity sheet's chart, the chart runs along the sleep's audio, so a gap between
 * recordings takes no room in it; the stretch never reaches over one anyway.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareSheet(
    state: ShareUiState,
    /** What the cards and caption say about the events, in the step's order. */
    labels: List<EventLabels>,
    loudness: SleepLoudness?,
    position: () -> Duration,
    onSelect: (start: Duration, end: Duration) -> Unit,
    onMargins: (leadIn: Duration, tail: Duration) -> Unit,
    onReset: () -> Unit,
    onFormat: (ShareFormat) -> Unit,
    onCaptionItem: (CaptionItem, Boolean) -> Unit,
    /** Plays or pauses a single event's stretch; null where nothing can play it, which hides the button. */
    onListen: (() -> Unit)?,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            val stretch = state.stretch
            val stretches = state.stretches
            if (stretch != null) {
                SingleStretch(stretch, state.preparing, state.items.single().timeline, loudness, position, onSelect)
            } else if (stretches != null) {
                SeveralStretches(stretches, state.preparing, onMargins)
            }

            ShareOptions(state, labels, onFormat, onCaptionItem, Modifier.padding(top = 16.dp))

            // Listening on the left, resetting and sharing on the right; when the three do not fit in
            // one line (long labels, a large font), the two on the right move to a line of their own.
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                if (stretch != null && onListen != null) {
                    OutlinedButton(onClick = onListen, enabled = !state.preparing) {
                        Icon(
                            painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(if (state.playing) R.string.pause else R.string.listen), maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    TextButton(onClick = onReset, enabled = !state.preparing && state.changed) {
                        Text(stringResource(R.string.reset), maxLines = 1)
                    }
                    Button(onClick = onShare, enabled = !state.preparing) {
                        if (state.preparing) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                        } else {
                            Icon(painterResource(R.drawable.ic_share), contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.share_event), maxLines = 1)
                    }
                }
            }
        }
    }
}

/** The stretch shared of a single event: its clock times and length, the chart and the range slider. */
@Composable
private fun SingleStretch(
    stretch: ShareStretch,
    preparing: Boolean,
    timeline: SleepTimeline,
    loudness: SleepLoudness?,
    position: () -> Duration,
    onSelect: (start: Duration, end: Duration) -> Unit,
) {
    val clock = LocalClockFormatter.current
    val resources = LocalResources.current
    val bounds = stretch.bounds
    val selected = stretch.selected
    fun clockAt(at: Duration) = if (timeline.pieces.isEmpty()) "" else clock.format(timeline.instantAt(at), seconds = true)

    Text(stringResource(R.string.share_title, clockAt(stretch.event.start)), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.share_stretch, clockAt(selected.start), clockAt(selected.end), formatEventLength(resources, stretch.length)),
        style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum")),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    ShareChart(stretch, loudness, position, clockAt = ::clockAt)

    // The slider counts seconds from the start of the bounds, in steps of a tenth of a second.
    val span = seconds(bounds.end - bounds.start)
    val rangeLabel = stringResource(R.string.share_range)
    RangeSlider(
        value = seconds(selected.start - bounds.start)..seconds(selected.end - bounds.start),
        onValueChange = { range -> onSelect(bounds.start + tenths(range.start), bounds.start + tenths(range.endInclusive)) },
        valueRange = 0f..span.coerceAtLeast(MIN_SPAN_SECONDS),
        enabled = !preparing,
        modifier = Modifier.semantics { contentDescription = rangeLabel },
    )
    Text(
        stringResource(R.string.share_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The stretches shared of several events: how many and how long together, and sliders for their lead-in and tail. */
@Composable
private fun SeveralStretches(stretches: ShareStretches, preparing: Boolean, onMargins: (leadIn: Duration, tail: Duration) -> Unit) {
    val resources = LocalResources.current
    val count = stretches.events.size
    Text(pluralStringResource(R.plurals.share_several_title, count, count), style = MaterialTheme.typography.titleMedium)
    Text(
        stringResource(R.string.share_several_length, formatEventLength(resources, stretches.length)),
        style = MaterialTheme.typography.bodySmall.merge(TextStyle(fontFeatureSettings = "tnum")),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    MarginSlider(R.string.settings_lead_in, stretches.leadIn, preparing) { onMargins(it, stretches.tail) }
    MarginSlider(R.string.settings_tail, stretches.tail, preparing) { onMargins(stretches.leadIn, it) }
    Text(
        stringResource(R.string.share_several_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A slider for a lead-in or tail of up to [ShareStretch.MARGIN], in tenths of a second, with its [label] and value. */
@Composable
private fun MarginSlider(@StringRes label: Int, value: Duration, preparing: Boolean, onChange: (Duration) -> Unit) {
    val resources = LocalResources.current
    val name = stringResource(label)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(
            formatEventLength(resources, value),
            style = MaterialTheme.typography.bodyMedium.merge(TextStyle(fontFeatureSettings = "tnum")),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = seconds(value),
        onValueChange = { onChange(tenths(it)) },
        valueRange = 0f..seconds(ShareStretch.MARGIN),
        enabled = !preparing,
        modifier = Modifier.semantics { contentDescription = name },
    )
}

/**
 * The format the events are shared in and, for a video, the items its caption names, each
 * checked while chosen, with the caption that results. An item none of [events] has (a name, a
 * transcript) cannot be chosen.
 */
@Composable
fun ShareOptions(
    state: ShareUiState,
    events: List<EventLabels>,
    onFormat: (ShareFormat) -> Unit,
    onCaptionItem: (CaptionItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(modifier) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            for ((index, format) in ShareFormat.entries.withIndex()) {
                SegmentedButton(
                    selected = state.format == format,
                    onClick = { onFormat(format) },
                    shape = SegmentedButtonDefaults.itemShape(index, ShareFormat.entries.size),
                    enabled = !state.preparing,
                ) {
                    Text(stringResource(if (format == ShareFormat.VIDEO) R.string.share_format_video else R.string.share_format_audio))
                }
            }
        }

        if (state.format == ShareFormat.AUDIO) {
            Text(
                stringResource(R.string.share_audio_hint),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            return@Column
        }

        Text(stringResource(R.string.share_caption), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        FlowRow {
            for (item in CHECKBOX_ORDER) {
                val available = when (item) {
                    CaptionItem.NAME -> events.any { it.name != null }
                    CaptionItem.TRANSCRIPT -> events.any { it.transcript != null }
                    else -> true
                }
                val checked = available && item in state.caption
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .toggleable(checked, enabled = available && !state.preparing, role = Role.Checkbox) { onCaptionItem(item, it) }
                        .padding(end = 12.dp),
                ) {
                    Checkbox(checked = checked, onCheckedChange = null, enabled = available, modifier = Modifier.padding(12.dp))
                    Text(
                        stringResource(item.label),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (available) colors.onSurface else colors.onSurface.copy(alpha = 0.38f),
                    )
                }
            }
        }
        Text(
            caption(events, state.caption) ?: stringResource(R.string.share_no_caption),
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(colors.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

/** The caption items in the order of their checkboxes: those on by default first. */
private val CHECKBOX_ORDER = listOf(CaptionItem.NAME, CaptionItem.TIME, CaptionItem.TRANSCRIPT, CaptionItem.SLEEP, CaptionItem.LENGTH)

/** The label of a caption item's checkbox. */
private val CaptionItem.label: Int
    @StringRes get() = when (this) {
        CaptionItem.NAME -> R.string.caption_name
        CaptionItem.TIME -> R.string.caption_time
        CaptionItem.SLEEP -> R.string.caption_sleep
        CaptionItem.LENGTH -> R.string.caption_length
        CaptionItem.TRANSCRIPT -> R.string.caption_transcript
    }

/**
 * The loudness across the stretch's bounds as bars, coloured where they are shared and grey
 * elsewhere, with the event outlined, the playhead and clock times along the axis.
 */
@Composable
private fun ShareChart(stretch: ShareStretch, loudness: SleepLoudness?, position: () -> Duration, clockAt: (Duration) -> String) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant, fontFeatureSettings = "tnum")
    val description = stringResource(R.string.share_chart)
    val bounds = stretch.bounds
    val selected = stretch.selected

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = description },
    ) {
        drawRect(colors.surfaceContainerLow)
        val ticksHeight = 18.dp.toPx()
        val top = 6.dp.toPx()
        val plotHeight = size.height - ticksHeight - top
        val spanNanos = (bounds.end - bounds.start).toNanos().coerceAtLeast(1)
        fun xOf(at: Duration) = (at - bounds.start).toNanos().toFloat() / spanNanos * size.width
        fun positionAt(share: Float) = bounds.start + Duration.ofNanos((spanNanos * share).roundToLong())

        if (loudness != null && !loudness.isEmpty) {
            val columnWidth = COLUMN_WIDTH.toPx()
            val count = (size.width / columnWidth).toInt().coerceAtLeast(1)
            val startNanos = bounds.start.toNanos()
            for (column in 0 until count) {
                val from = startNanos + spanNanos * column / count
                val until = startNanos + spanNanos * (column + 1) / count
                val db = loudness.loudestInNanos(from, until)
                if (db.isInfinite()) continue
                val share = ((db - loudness.lowDb) / (loudness.highDb - loudness.lowDb)).coerceIn(0f, 1f)
                val height = (share * plotHeight).coerceAtLeast(1f)
                val middle = (from + until) / 2
                val shared = middle >= selected.start.toNanos() && middle < selected.end.toNanos()
                drawRect(
                    if (shared) colors.primary else colors.outlineVariant,
                    topLeft = Offset(column * size.width / count, top + plotHeight - height),
                    size = Size((size.width / count - 1f).coerceAtLeast(1f), height),
                )
            }
        }

        val event = stretch.event
        val left = xOf(event.start)
        val right = xOf(event.end)
        drawRect(
            colors.onSurface,
            topLeft = Offset(left, top),
            size = Size((right - left).coerceAtLeast(2.dp.toPx()), plotHeight),
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
        )

        val playhead = position()
        if (playhead >= bounds.start && playhead <= bounds.end) {
            drawRect(colors.onSurface, topLeft = Offset(xOf(playhead) - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), top + plotHeight))
        }

        // Each time is named once, and a label that would touch the one before is left out.
        var previous: String? = null
        var previousRight = Float.NEGATIVE_INFINITY
        for (tick in 0..TICKS) {
            val share = tick / TICKS.toFloat()
            val label = clockAt(positionAt(share))
            if (label == previous) continue
            val text = textMeasurer.measure(label, labelStyle)
            val inset = 6.dp.toPx()
            val x = (share * size.width - text.size.width / 2).coerceIn(inset, size.width - text.size.width - inset)
            if (x < previousRight + inset) continue
            previous = label
            previousRight = x + text.size.width
            drawText(text, topLeft = Offset(x, size.height - ticksHeight + 3.dp.toPx()))
        }
    }
}

/** [duration] in seconds. */
private fun seconds(duration: Duration): Float = duration.toMillis() / 1_000f

/** [seconds] rounded to a tenth of a second. */
private fun tenths(seconds: Float): Duration = Duration.ofMillis((seconds * 10).roundToLong() * 100)

/** A range for the slider also when the event has no audio around it. */
private const val MIN_SPAN_SECONDS = 0.1f

/** The width of a bar of the chart. */
private val COLUMN_WIDTH = 3.dp

/** The clock labels below the chart divide it into this many parts. */
private const val TICKS = 4
