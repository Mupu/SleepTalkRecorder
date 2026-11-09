package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatDuration
import java.time.Duration
import java.time.ZoneId

/**
 * The loudness of a sleep over its audio, as in the sleep screen's timeline: the [window] shown
 * large, with everything that is not an event grey, events in their kind's colour, the baseline as
 * a dashed line and the playhead; below it the whole sleep as an overview strip with the window
 * marked, and buttons to zoom.
 *
 * Dragging the large timeline pans it, pinching zooms it, and a tap moves the playhead there.
 * Tapping or dragging the overview moves the window and the playhead along.
 */
@Composable
fun LoudnessTimeline(
    timeline: SleepTimeline,
    loudness: SleepLoudness?,
    events: List<SleepEvent>,
    position: Duration,
    window: TimelineWindow,
    onWindowChange: (TimelineWindow) -> Unit,
    onSeek: (Duration) -> Unit,
    onScrub: (Duration) -> Unit,
    onScrubFinished: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val clock = LocalClockFormatter.current
    val resources = LocalResources.current
    val zone = ZoneId.systemDefault()
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 10.sp, color = colors.onSurfaceVariant, fontFeatureSettings = "tnum")
    // The gesture handlers live as long as the timeline; they read the latest state and callbacks.
    val currentWindow by rememberUpdatedState(window)
    val changeWindow by rememberUpdatedState(onWindowChange)
    val seek by rememberUpdatedState(onSeek)
    val scrub by rememberUpdatedState(onScrub)
    val finishScrub by rememberUpdatedState(onScrubFinished)
    val description = stringResource(R.string.timeline)

    Column {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    detectTapGestures { offset -> seek(currentWindow.positionAt(offset.x / size.width.toDouble())) }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val width = size.width.toDouble()
                        val panned = currentWindow.pan(Duration.ofMillis((-pan.x / width * currentWindow.span.toMillis()).toLong()))
                        changeWindow(panned.zoom(1.0 / zoom, (centroid.x / width).coerceIn(0.0, 1.0)))
                    }
                },
        ) {
            drawRect(colors.surfaceContainerLow)
            val ticksHeight = 18.dp.toPx()
            val plot = Size(size.width, size.height - ticksHeight - 6.dp.toPx())
            drawLoudness(loudness, events, window, plot, top = 6.dp.toPx(), colors, step = 2.dp.toPx(), baseline = true)
            drawPlayhead(window, position, top = 0f, height = plot.height + 6.dp.toPx(), colors.onSurface)

            // Clock-time ticks at round times, labelled with the time recorded there.
            val start = timeline.takeIf { it.pieces.isNotEmpty() }?.instantAt(window.start) ?: return@Canvas
            val room = size.width / 64.dp.toPx()
            val step = TICK_STEPS.firstOrNull { window.span.toMillis() / it.toMillis() <= room } ?: TICK_STEPS.last()
            val stepMillis = step.toMillis()
            val startMillis = start.toEpochMilli()
            var tick = Duration.ofMillis(Math.floorDiv(startMillis + stepMillis - 1, stepMillis) * stepMillis - startMillis)
            while (window.start + tick <= window.end) {
                val at = window.start + tick
                val x = (window.shareOf(at) * size.width).toFloat()
                drawLine(colors.outlineVariant, Offset(x, size.height - ticksHeight), Offset(x, size.height - ticksHeight + 4.dp.toPx()))
                val label = clock.format(timeline.instantAt(at), zone, seconds = step < Duration.ofMinutes(1))
                val text = textMeasurer.measure(label, labelStyle)
                // Kept clear of the rounded corners, which would clip a label at the very edge.
                val inset = 6.dp.toPx()
                val left = (x - text.size.width / 2).coerceIn(inset, size.width - text.size.width - inset)
                drawText(text, topLeft = Offset(left, size.height - ticksHeight + 5.dp.toPx()))
                tick += step
            }
        }

        Canvas(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp)
                .height(24.dp)
                .pointerInput(Unit) {
                    // A press or drag moves the window and the playhead; letting go plays from there.
                    awaitEachGesture {
                        val whole = TimelineWindow.whole(currentWindow.total)
                        fun moveTo(x: Float) {
                            val target = whole.positionAt((x / size.width.toDouble()).coerceIn(0.0, 1.0))
                            changeWindow(currentWindow.centredOn(target))
                            scrub(target)
                        }
                        moveTo(awaitFirstDown().position.x)
                        do {
                            val event = awaitPointerEvent()
                            event.changes.forEach { change ->
                                if (change.pressed) {
                                    moveTo(change.position.x)
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        finishScrub()
                    }
                },
        ) {
            val whole = TimelineWindow.whole(window.total)
            drawLoudness(loudness, events, whole, size, top = 0f, colors, step = 1.dp.toPx(), baseline = false)
            drawPlayhead(whole, position, top = 0f, height = size.height, colors.onSurface)
            val left = (whole.shareOf(window.start) * size.width).toFloat()
            val right = (whole.shareOf(window.end) * size.width).toFloat()
            val stroke = 1.5.dp.toPx()
            drawRect(
                colors.onSurface,
                topLeft = Offset(left + stroke / 2, stroke / 2),
                size = Size((right - left - stroke).coerceAtLeast(3.dp.toPx()), size.height - stroke),
                style = Stroke(stroke),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onWindowChange(window.zoom(2.0, window.shareOf(position).coerceIn(0.0, 1.0))) }) {
                Icon(painterResource(R.drawable.ic_zoom_out), contentDescription = stringResource(R.string.zoom_out))
            }
            IconButton(onClick = { onWindowChange(window.zoom(0.5, window.shareOf(position).coerceIn(0.0, 1.0))) }) {
                Icon(painterResource(R.drawable.ic_zoom_in), contentDescription = stringResource(R.string.zoom_in))
            }
            IconButton(onClick = { onWindowChange(TimelineWindow.whole(window.total)) }) {
                Icon(painterResource(R.drawable.ic_fit_screen), contentDescription = stringResource(R.string.zoom_fit))
            }
            if (timeline.pieces.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.timeline_span,
                        clock.format(timeline.instantAt(window.start), zone),
                        clock.format(timeline.instantAt(window.end), zone),
                        formatDuration(resources, window.span),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

/**
 * Draws the loudness of [window] into [plot] below [top]: one bar per [step] pixels, as high as the
 * loudest level in its stretch, coloured when an event lies in it and grey otherwise.
 */
private fun DrawScope.drawLoudness(
    loudness: SleepLoudness?,
    events: List<SleepEvent>,
    window: TimelineWindow,
    plot: Size,
    top: Float,
    colors: ColorScheme,
    step: Float,
    baseline: Boolean,
) {
    if (loudness == null || window.span <= Duration.ZERO) return
    fun heightOf(db: Float) = ((db - loudness.lowDb) / (loudness.highDb - loudness.lowDb)).coerceIn(0f, 1f) * plot.height

    var x = 0f
    var nextEvent = 0
    while (x < plot.width) {
        val from = window.positionAt(x / plot.width.toDouble())
        val until = window.positionAt(((x + step) / plot.width).toDouble().coerceAtMost(1.0))
        val loudest = loudness.loudestIn(from, until)
        if (loudest != null) {
            while (nextEvent < events.size && events[nextEvent].end <= from) nextEvent++
            val event = events.getOrNull(nextEvent)?.takeIf { it.start < until }
            val color = event?.let { kindColor(it.kindColor, colors) } ?: colors.outlineVariant
            val height = heightOf(loudest).coerceAtLeast(1f)
            drawRect(color, topLeft = Offset(x, top + plot.height - height), size = Size((step - 1f).coerceAtLeast(1f), height))
        }
        x += step
    }

    if (baseline) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        var previous: Offset? = null
        var px = 0f
        while (px <= plot.width) {
            val db = loudness.baselineAt(window.positionAt(px / plot.width.toDouble())) ?: break
            val point = Offset(px, top + plot.height - heightOf(db))
            previous?.let { drawLine(colors.onSurfaceVariant, it, point, strokeWidth = 1.dp.toPx(), pathEffect = dash) }
            previous = point
            px += 4.dp.toPx()
        }
    }
}

private fun DrawScope.drawPlayhead(window: TimelineWindow, position: Duration, top: Float, height: Float, color: Color) {
    val share = window.shareOf(position)
    if (share !in 0.0..1.0) return
    val x = (share * size.width).toFloat()
    drawRect(color, topLeft = Offset(x - 1.dp.toPx(), top), size = Size(2.dp.toPx(), height))
}

/** The colour of an event kind: a role of the theme, which follows light and dark mode, or `#RRGGBB`. */
internal fun kindColor(spec: String, colors: ColorScheme): Color = when (spec) {
    "primary" -> colors.primary
    "secondary" -> colors.secondary
    "tertiary" -> colors.tertiary
    else -> runCatching { Color(spec.toColorInt()) }.getOrDefault(colors.primary)
}

/** Distances between clock ticks; the shortest that leaves room for the labels is used. */
private val TICK_STEPS = listOf(10L, 30L, 60L, 300L, 600L, 900L, 1_800L, 3_600L, 7_200L).map(Duration::ofSeconds)
