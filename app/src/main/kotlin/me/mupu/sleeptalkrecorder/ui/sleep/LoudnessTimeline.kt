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
import androidx.compose.material3.FilledIconToggleButton
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
import androidx.compose.ui.graphics.drawscope.clipRect
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
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatDuration
import java.time.Duration
import java.time.ZoneId

/**
 * The loudness of a sleep over the clock, as in the sleep screen's timeline: the [window] of the
 * timeline's clock axis shown large, with everything that is not an event grey, events in their
 * kind's colour, the gaps between recordings and the stretches in which the microphone was
 * [unavailable] hatched, the baseline as a dashed line and the playhead; below it the whole sleep
 * as an overview strip with the window marked, and buttons to zoom. [position] is a position in
 * the sleep's audio, as the player plays it.
 *
 * Dragging the large timeline pans it, pinching zooms it, and a tap moves the playhead there.
 * Tapping or dragging the overview moves the window and the playhead along. Every such change of
 * the window by hand is reported through [onWindowChange]; the auto zoom button shows [autoZoom]
 * and switches it.
 */
@Composable
fun LoudnessTimeline(
    timeline: SleepTimeline,
    loudness: SleepLoudness?,
    events: List<SleepEvent>,
    unavailable: List<Stretch>,
    position: Duration,
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

    Column {
        Canvas(
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
            drawRect(colors.surfaceContainerLow)
            val ticksHeight = 18.dp.toPx()
            val plot = Size(size.width, size.height - ticksHeight - 6.dp.toPx())
            val axis = Axis(timeline, unavailable)
            drawLoudness(axis, loudness, events, window, plot, top = 6.dp.toPx(), colors, step = 2.dp.toPx(), baseline = true)
            drawPlayhead(window, timeline.clockOf(position), top = 0f, height = plot.height + 6.dp.toPx(), colors.onSurface)

            // Clock-time ticks at round times, labelled with the time recorded there.
            val start = timeline.takeIf { it.pieces.isNotEmpty() }?.instantAtClock(window.start) ?: return@Canvas
            val room = size.width / 64.dp.toPx()
            val step = TICK_STEPS.firstOrNull { window.span.toMillis() / it.toMillis() <= room } ?: TICK_STEPS.last()
            val stepMillis = step.toMillis()
            val startMillis = start.toEpochMilli()
            var tick = Duration.ofMillis(Math.floorDiv(startMillis + stepMillis - 1, stepMillis) * stepMillis - startMillis)
            while (window.start + tick <= window.end) {
                val at = window.start + tick
                val x = (window.shareOf(at) * size.width).toFloat()
                drawLine(colors.outlineVariant, Offset(x, size.height - ticksHeight), Offset(x, size.height - ticksHeight + 4.dp.toPx()))
                val label = clock.format(timeline.instantAtClock(at), zone, seconds = step < Duration.ofMinutes(1))
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
            val whole = TimelineWindow.whole(window.total)
            drawLoudness(Axis(timeline, unavailable), loudness, events, whole, size, top = 0f, colors, step = 1.dp.toPx(), baseline = false)
            drawPlayhead(whole, timeline.clockOf(position), top = 0f, height = size.height, colors.onSurface)
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
            val playhead = window.shareOf(timeline.clockOf(position)).coerceIn(0.0, 1.0)
            IconButton(onClick = { onWindowChange(window.zoom(2.0, playhead)) }) {
                Icon(painterResource(R.drawable.ic_zoom_out), contentDescription = stringResource(R.string.zoom_out))
            }
            IconButton(onClick = { onWindowChange(window.zoom(0.5, playhead)) }) {
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

/** The clock axis of a sleep's [timeline], with the stretches of it that hold no sound from the room. */
private class Axis(val timeline: SleepTimeline, private val unavailable: List<Stretch>) {

    /** Whether the moment at [clock] lies in a gap or where the microphone was unavailable. */
    fun isInterrupted(clock: Duration): Boolean {
        if (timeline.gaps.any { clock >= it.start && clock < it.end }) return true
        val position = timeline.positionAtClock(clock)
        return unavailable.any { position >= it.start && position < it.end }
    }
}

/**
 * Draws the loudness of [window], a stretch of the clock axis, into [plot] below [top]: one bar per
 * [step] pixels, as high as the loudest level in its stretch, coloured when an event lies in it and
 * grey otherwise; gaps and stretches without the microphone are hatched.
 */
private fun DrawScope.drawLoudness(
    axis: Axis,
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

    val timeline = axis.timeline
    // Runs of columns to hatch, from their left to their right edge in pixels.
    val hatched = ArrayList<Pair<Float, Float>>()
    var x = 0f
    var nextEvent = 0
    while (x < plot.width) {
        val clockFrom = window.positionAt(x / plot.width.toDouble())
        val clockUntil = window.positionAt(((x + step) / plot.width).toDouble().coerceAtMost(1.0))
        if (axis.isInterrupted(clockFrom + (clockUntil - clockFrom).dividedBy(2))) {
            val last = hatched.lastOrNull()
            if (last != null && last.second == x) hatched[hatched.lastIndex] = last.first to x + step else hatched += x to x + step
            x += step
            continue
        }
        val from = timeline.positionAtClock(clockFrom)
        val until = timeline.positionAtClock(clockUntil)
        val loudest = if (until > from) loudness.loudestIn(from, until) else null
        if (loudest != null) {
            while (nextEvent < events.size && events[nextEvent].end <= from) nextEvent++
            val event = events.getOrNull(nextEvent)?.takeIf { it.start < until }
            val color = event?.let { kindColor(it.kindColor, colors) } ?: colors.outlineVariant
            val height = heightOf(loudest).coerceAtLeast(1f)
            drawRect(color, topLeft = Offset(x, top + plot.height - height), size = Size((step - 1f).coerceAtLeast(1f), height))
        }
        x += step
    }
    for ((left, right) in hatched) drawHatch(left, right.coerceAtMost(plot.width), top, plot.height, colors.outline)

    if (baseline) {
        val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
        var previous: Offset? = null
        var px = 0f
        while (px <= plot.width) {
            val clock = window.positionAt(px / plot.width.toDouble())
            val db = if (axis.isInterrupted(clock)) null else loudness.baselineAt(timeline.positionAtClock(clock))
            if (db == null) {
                // The line breaks where nothing was recorded.
                previous = null
                px += 4.dp.toPx()
                continue
            }
            val point = Offset(px, top + plot.height - heightOf(db))
            previous?.let { drawLine(colors.onSurfaceVariant, it, point, strokeWidth = 1.dp.toPx(), pathEffect = dash) }
            previous = point
            px += 4.dp.toPx()
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
