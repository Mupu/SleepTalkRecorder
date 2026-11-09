package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
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
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.KindColor
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.ThemeRole
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.ui.format.ClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatDuration
import java.time.Duration
import java.time.ZoneId

/**
 * The loudness of a sleep over the clock, as in the sleep screen's timeline: the [window] of the
 * timeline's clock axis shown large, with everything that is not an event grey, events in their
 * kind's colour, the gaps between recordings and the stretches in which the microphone was
 * [unavailable] hatched, the baseline as a dashed line and the playhead; below it the whole sleep
 * as an overview strip with the window marked, and buttons to zoom. [position] gives the position
 * in the sleep's audio, as the player plays it.
 *
 * Dragging the large timeline pans it, pinching zooms it, and a tap moves the playhead there.
 * Tapping or dragging the overview moves the window and the playhead along. Every such change of
 * the window by hand is reported through [onWindowChange]; the auto zoom button shows [autoZoom]
 * and switches it.
 *
 * The bars are computed once for each window and size and then only drawn again; the playhead
 * and the overview's window mark are layers of their own, so a moving playhead redraws nothing
 * else, and the position is read only while drawing, so it recomposes nothing.
 */
@Composable
fun LoudnessTimeline(
    timeline: SleepTimeline,
    loudness: SleepLoudness?,
    events: List<SleepEvent>,
    unavailable: List<Stretch>,
    position: () -> Duration,
    window: TimelineWindow,
    onWindowChange: (TimelineWindow) -> Unit,
    autoZoom: Boolean,
    onAutoZoomChange: (Boolean) -> Unit,
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
    // Only where the events lie and their colours change the bars; a new transcript changes neither.
    val drawn = remember(events) { DrawnEvents(events.map { it.stretch }, events.map { it.kindColor }) }

    val bars = remember(timeline, loudness, drawn, unavailable, window, colors, clock, zone, textMeasurer, labelStyle) {
        Modifier.drawWithCache {
            val ticksHeight = 18.dp.toPx()
            val top = 6.dp.toPx()
            val plot = Size(size.width, size.height - ticksHeight - top)
            val step = 2.dp.toPx()
            val columns = loudness?.let {
                LoudnessColumns.of(timeline, it, drawn.stretches, unavailable, window, plot.width, plot.height, step, baselineStep = 4.dp.toPx())
            }
            val barColors = columns?.let { barColors(it, drawn.colors, colors) }
            val ticks = ticks(timeline, window, clock, zone, room = size.width / 64.dp.toPx()).map { (share, label) ->
                (share * size.width).toFloat() to textMeasurer.measure(label, labelStyle)
            }
            onDrawBehind {
                drawRect(colors.surfaceContainerLow)
                if (columns != null && barColors != null) drawColumns(columns, barColors, top, plot.height, step, colors, baseline = true)
                // Clock-time ticks at round times, labelled with the time recorded there.
                val inset = 6.dp.toPx()
                for ((x, text) in ticks) {
                    drawLine(colors.outlineVariant, Offset(x, size.height - ticksHeight), Offset(x, size.height - ticksHeight + 4.dp.toPx()))
                    // Kept clear of the rounded corners, which would clip a label at the very edge.
                    val left = (x - text.size.width / 2).coerceIn(inset, size.width - text.size.width - inset)
                    drawText(text, topLeft = Offset(left, size.height - ticksHeight + 5.dp.toPx()))
                }
            }
        }
    }
    // The whole sleep does not change with the window, so the overview's bars stay as they are.
    val overviewBars = remember(timeline, loudness, drawn, unavailable, colors) {
        Modifier.drawWithCache {
            val step = 1.dp.toPx()
            val whole = TimelineWindow.whole(timeline.clockDuration)
            val columns = loudness?.let {
                LoudnessColumns.of(timeline, it, drawn.stretches, unavailable, whole, size.width, size.height, step, baselineStep = null)
            }
            val barColors = columns?.let { barColors(it, drawn.colors, colors) }
            onDrawBehind {
                if (columns != null && barColors != null) drawColumns(columns, barColors, top = 0f, size.height, step, colors, baseline = false)
            }
        }
    }

    Column {
        Box(
            Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(12.dp))
                .semantics { contentDescription = description }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        seek(timeline.positionAtClock(currentWindow.positionAt(offset.x / size.width.toDouble())))
                    }
                }
                .pointerInput(Unit) {
                    detectTransformGestures { centroid, pan, zoom, _ ->
                        val width = size.width.toDouble()
                        val panned = currentWindow.pan(Duration.ofMillis((-pan.x / width * currentWindow.span.toMillis()).toLong()))
                        changeWindow(panned.zoom(1.0 / zoom, (centroid.x / width).coerceIn(0.0, 1.0)))
                    }
                },
        ) {
            Spacer(Modifier.matchParentSize().then(bars))
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer()
                    .drawBehind {
                        // Down to the ticks, through the 6 dp above the bars.
                        val height = size.height - 18.dp.toPx()
                        drawPlayhead(window, timeline.clockOf(position()), top = 0f, height = height, colors.onSurface)
                    },
            )
        }

        Box(
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
                            scrub(timeline.positionAtClock(target))
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
            Spacer(Modifier.matchParentSize().then(overviewBars))
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer()
                    .drawBehind {
                        val whole = TimelineWindow.whole(window.total)
                        drawPlayhead(whole, timeline.clockOf(position()), top = 0f, height = size.height, colors.onSurface)
                        val left = (whole.shareOf(window.start) * size.width).toFloat()
                        val right = (whole.shareOf(window.end) * size.width).toFloat()
                        val stroke = 1.5.dp.toPx()
                        drawRect(
                            colors.onSurface,
                            topLeft = Offset(left + stroke / 2, stroke / 2),
                            size = Size((right - left - stroke).coerceAtLeast(3.dp.toPx()), size.height - stroke),
                            style = Stroke(stroke),
                        )
                    },
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            // Zooming keeps the playhead in place; the position is read only when a button is tapped.
            fun playhead() = window.shareOf(timeline.clockOf(position())).coerceIn(0.0, 1.0)
            IconButton(onClick = { onWindowChange(window.zoom(2.0, playhead())) }) {
                Icon(painterResource(R.drawable.ic_zoom_out), contentDescription = stringResource(R.string.zoom_out))
            }
            IconButton(onClick = { onWindowChange(window.zoom(0.5, playhead())) }) {
                Icon(painterResource(R.drawable.ic_zoom_in), contentDescription = stringResource(R.string.zoom_in))
            }
            IconButton(onClick = { onWindowChange(TimelineWindow.whole(window.total)) }) {
                Icon(painterResource(R.drawable.ic_fit_screen), contentDescription = stringResource(R.string.zoom_fit))
            }
            FilledIconToggleButton(checked = autoZoom, onCheckedChange = onAutoZoomChange) {
                Icon(painterResource(R.drawable.ic_center_focus_strong), contentDescription = stringResource(R.string.auto_zoom))
            }
            if (timeline.pieces.isNotEmpty()) {
                Text(
                    stringResource(
                        R.string.timeline_span,
                        clock.format(timeline.instantAtClock(window.start), zone),
                        clock.format(timeline.instantAtClock(window.end), zone),
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
 * The clock-time ticks of [window] at round times, as shares of its width with their labels: the
 * time recorded there. The shortest distance between ticks that leaves [room] labels across the
 * width is used.
 */
private fun ticks(timeline: SleepTimeline, window: TimelineWindow, clock: ClockFormatter, zone: ZoneId, room: Float): List<Pair<Double, String>> {
    val start = timeline.takeIf { it.pieces.isNotEmpty() }?.instantAtClock(window.start) ?: return emptyList()
    val step = TICK_STEPS.firstOrNull { window.span.toMillis() / it.toMillis() <= room } ?: TICK_STEPS.last()
    val stepMillis = step.toMillis()
    val startMillis = start.toEpochMilli()
    var tick = Duration.ofMillis(Math.floorDiv(startMillis + stepMillis - 1, stepMillis) * stepMillis - startMillis)
    val ticks = ArrayList<Pair<Double, String>>()
    while (window.start + tick <= window.end) {
        val at = window.start + tick
        ticks += window.shareOf(at) to clock.format(timeline.instantAtClock(at), zone, seconds = step < Duration.ofMinutes(1))
        tick += step
    }
    return ticks
}

/** What the bars show of the events: their stretches and, in the same order, their kinds' colours. */
private data class DrawnEvents(val stretches: List<Stretch>, val colors: List<KindColor>)

/** The colour of each bar of [columns]: the kind colour of its event in [eventColors], or grey outside events. */
private fun barColors(columns: LoudnessColumns, eventColors: List<KindColor>, colors: ColorScheme): Array<Color> =
    Array(columns.bars) { bar -> eventColors.getOrNull(columns.events[bar])?.let { kindColor(it, colors) } ?: colors.outlineVariant }

/**
 * Draws [columns] into a plot of [height] below [top]: the bars in [barColors], [step] pixels
 * apart, the hatched runs and, if wanted, the [baseline] as a dashed line that breaks where
 * nothing was recorded.
 */
private fun DrawScope.drawColumns(
    columns: LoudnessColumns,
    barColors: Array<Color>,
    top: Float,
    height: Float,
    step: Float,
    colors: ColorScheme,
    baseline: Boolean,
) {
    val barWidth = (step - 1f).coerceAtLeast(1f)
    for (bar in 0 until columns.bars) {
        val barHeight = columns.heights[bar]
        drawRect(barColors[bar], topLeft = Offset(columns.lefts[bar], top + height - barHeight), size = Size(barWidth, barHeight))
    }
    val hatched = columns.hatched
    for (run in hatched.indices step 2) drawHatch(hatched[run], hatched[run + 1], top, height, colors.outline)

    if (baseline) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        val points = columns.baseline
        for (point in 2 until points.size step 2) {
            val from = points[point - 1]
            val to = points[point + 1]
            if (from.isNaN() || to.isNaN()) continue
            drawLine(
                colors.onSurfaceVariant,
                Offset(points[point - 2], top + from),
                Offset(points[point], top + to),
                strokeWidth = 1.dp.toPx(),
                pathEffect = dash,
            )
        }
    }
}

/** Diagonal lines from [left] to [right] and [top] to [top] + [height], marking a stretch without sound from the room. */
private fun DrawScope.drawHatch(left: Float, right: Float, top: Float, height: Float, color: Color) {
    val spacing = 5.dp.toPx()
    clipRect(left, top, right, top + height) {
        // Each line rises to the right by its height; starting a height early covers the left corner,
        // and starting on the spacing grid keeps the lines of neighbouring runs in step.
        var start = left - height - (left - height).mod(spacing)
        while (start < right) {
            drawLine(color, Offset(start, top + height), Offset(start + height, top), strokeWidth = 1.dp.toPx())
            start += spacing
        }
    }
}

/** Draws the playhead at [clock], a moment on the clock axis, if the [window] shows it. */
private fun DrawScope.drawPlayhead(window: TimelineWindow, clock: Duration, top: Float, height: Float, color: Color) {
    val share = window.shareOf(clock)
    if (share !in 0.0..1.0) return
    val x = (share * size.width).toFloat()
    drawRect(color, topLeft = Offset(x - 1.dp.toPx(), top), size = Size(2.dp.toPx(), height))
}

/** The colour [color] has in the theme's [colors]. */
internal fun kindColor(color: KindColor, colors: ColorScheme): Color = when (color) {
    is KindColor.Theme -> when (color.role) {
        ThemeRole.PRIMARY -> colors.primary
        ThemeRole.SECONDARY -> colors.secondary
        ThemeRole.TERTIARY -> colors.tertiary
    }
    is KindColor.Fixed -> Color(0xFF000000.toInt() or color.rgb)
}

/** Distances between clock ticks; the shortest that leaves room for the labels is used. */
private val TICK_STEPS = listOf(10L, 30L, 60L, 300L, 600L, 900L, 1_800L, 3_600L, 7_200L).map(Duration::ofSeconds)
