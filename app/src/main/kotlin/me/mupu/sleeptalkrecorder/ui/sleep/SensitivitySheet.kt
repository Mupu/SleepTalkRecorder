package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import java.text.NumberFormat
import java.time.Duration
import kotlin.math.roundToInt

/**
 * The sensitivity of a sleep, or of the stretch around one event: the stretch's loudness with the
 * baseline and the start threshold above it, the events found with the slider's setting, and how
 * many there are now and were before. Every move of the slider re-scans the stretch; [onApply]
 * stores the setting and searches the sleep again.
 *
 * The chart runs along the sleep's audio, as the player plays it, so a gap between recordings
 * takes no room in it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SensitivitySheet(
    state: SensitivityUiState,
    timeline: SleepTimeline,
    position: () -> Duration,
    onThreshold: (Float) -> Unit,
    onReset: () -> Unit,
    onListen: () -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
) {
    val clock = LocalClockFormatter.current
    val resources = LocalResources.current
    val colors = MaterialTheme.colorScheme
    val window = state.editor.window
    val event = state.event
    val hasClock = timeline.pieces.isNotEmpty()
    // Seconds matter around an event; across a whole sleep they only crowd the labels.
    fun clockAt(at: Duration) = if (hasClock) clock.format(timeline.instantAt(at), seconds = event != null) else ""

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                if (event != null) stringResource(R.string.sensitivity_title_event, clockAt(event.start)) else stringResource(R.string.sensitivity_sleep),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                if (event != null) {
                    stringResource(R.string.sensitivity_window, clockAt(window.start), clockAt(window.end))
                } else {
                    stringResource(R.string.sensitivity_sleep_only)
                },
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            SensitivityChartView(state, position, clockAt = ::clockAt)
            Legend()

            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(R.string.less_sensitive), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(stringResource(R.string.more_sensitive), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            // Left is less sensitive, so the slider runs from the highest threshold to the lowest.
            Slider(
                value = (MAX_THRESHOLD_DB - state.thresholdDb).coerceIn(0f, MAX_THRESHOLD_DB - MIN_THRESHOLD_DB),
                onValueChange = { onThreshold(((MAX_THRESHOLD_DB - it) * 2).roundToInt() / 2f) },
                valueRange = 0f..(MAX_THRESHOLD_DB - MIN_THRESHOLD_DB),
                enabled = !state.applying,
            )
            val number = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
            Text(stringResource(R.string.sensitivity_value, number.format(state.thresholdDb)), style = MaterialTheme.typography.labelLarge)
            val found = state.chart?.found ?: state.before
            Text(
                pluralStringResource(
                    if (event != null) R.plurals.sensitivity_result_event else R.plurals.sensitivity_result_sleep,
                    found,
                    found,
                    state.before,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
            )
            Text(
                stringResource(if (event != null) R.string.sensitivity_scope_event else R.string.sensitivity_scope_sleep),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            // Listening on the left, resetting and applying on the right; when the three do not fit in
            // one line (long labels, a large font), the two on the right move to a line of their own.
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 16.dp),
            ) {
                if (event != null) {
                    OutlinedButton(onClick = onListen) {
                        Icon(painterResource(R.drawable.ic_play), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.listen), maxLines = 1)
                    }
                }
                Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    TextButton(onClick = onReset, enabled = !state.applying) { Text(stringResource(R.string.reset), maxLines = 1) }
                    Button(onClick = onApply, enabled = state.changed && !state.applying) { Text(stringResource(R.string.apply), maxLines = 1) }
                }
            }
        }
    }
}

/**
 * The stretch's loudness as bars, coloured where an event is found and grey elsewhere, the
 * baseline dashed and the start threshold above it as a line; the event the sheet was opened from
 * is outlined, and clock times label the axis.
 */
@Composable
private fun SensitivityChartView(state: SensitivityUiState, position: () -> Duration, clockAt: (Duration) -> String) {
    val colors = MaterialTheme.colorScheme
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant, fontFeatureSettings = "tnum")
    val description = stringResource(R.string.sensitivity_chart)
    val editor = state.editor

    Canvas(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .clip(RoundedCornerShape(12.dp))
            .semantics { contentDescription = description },
    ) {
        drawRect(colors.surfaceContainerLow)
        val chart = state.chart ?: return@Canvas
        val ticksHeight = 18.dp.toPx()
        val top = 6.dp.toPx()
        val plot = Size(size.width, size.height - ticksHeight - top)
        val count = chart.level.size
        val columnWidth = plot.width / count
        fun yOf(db: Float) = top + plot.height - ((db - chart.lowDb) / (chart.highDb - chart.lowDb)).coerceIn(0f, 1f) * plot.height
        fun xOf(column: Int) = (column + 0.5f) * columnWidth

        for (column in 0 until count) {
            val db = chart.level[column]
            if (db.isInfinite()) continue
            val y = yOf(db)
            drawRect(
                if (chart.inEvent[column]) colors.primary else colors.outlineVariant,
                topLeft = Offset(column * columnWidth, y),
                size = Size((columnWidth - 1f).coerceAtLeast(1f), (top + plot.height - y).coerceAtLeast(1f)),
            )
        }

        fun line(values: (Int) -> Float, color: Color, width: Float, dash: PathEffect?) {
            val path = Path()
            for (column in 0 until count) {
                val point = Offset(xOf(column), yOf(values(column)))
                if (column == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
            }
            drawPath(path, color, style = Stroke(width, pathEffect = dash))
        }
        line({ chart.baseline[it] }, colors.onSurfaceVariant, 1.dp.toPx(), PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
        line({ chart.baseline[it] + chart.threshold[it] }, colors.error, 1.5.dp.toPx(), null)

        state.event?.let { event ->
            val left = editor.shareOf(event.start) * size.width
            val right = editor.shareOf(event.end) * size.width
            drawRect(
                colors.onSurface,
                topLeft = Offset(left, top),
                size = Size((right - left).coerceAtLeast(2.dp.toPx()), plot.height),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 2.dp.toPx()))),
            )
        }

        val playhead = editor.shareOf(position())
        if (playhead in 0f..1f) {
            drawRect(colors.onSurface, topLeft = Offset(playhead * size.width - 1.dp.toPx(), 0f), size = Size(2.dp.toPx(), top + plot.height))
        }

        // Across a few seconds, neighbouring labels can fall in the same second, and a large font can
        // make them overlap: each time is named once, and a label that would touch the one before is left out.
        var previous: String? = null
        var previousRight = Float.NEGATIVE_INFINITY
        for (tick in 0..TICKS) {
            val share = tick / TICKS.toFloat()
            val label = clockAt(editor.positionAt(share))
            if (label == previous) continue
            val text = textMeasurer.measure(label, labelStyle)
            val inset = 6.dp.toPx()
            val left = (share * size.width - text.size.width / 2).coerceIn(inset, size.width - text.size.width - inset)
            if (left < previousRight + inset) continue
            previous = label
            previousRight = left + text.size.width
            drawText(text, topLeft = Offset(left, size.height - ticksHeight + 3.dp.toPx()))
        }
    }
}

/** What the chart's colours and lines mean. */
@Composable
private fun Legend() {
    val colors = MaterialTheme.colorScheme
    // Whole items move to a second line when they do not fit side by side.
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 6.dp)) {
        LegendItem(colors.primary, stringResource(R.string.sensitivity_found), height = 10.dp)
        LegendItem(colors.onSurfaceVariant, stringResource(R.string.baseline), height = 2.dp)
        LegendItem(colors.error, stringResource(R.string.sensitivity_line), height = 2.dp)
    }
}

@Composable
private fun LegendItem(color: Color, label: String, height: Dp) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = height).background(color, RoundedCornerShape(1.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** The lowest and highest start threshold the slider sets, in dB above the baseline. */
private const val MIN_THRESHOLD_DB = 3f
private const val MAX_THRESHOLD_DB = 30f

/** The clock labels below the chart divide it into this many parts. */
private const val TICKS = 4
