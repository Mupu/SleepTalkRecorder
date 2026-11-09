package me.mupu.sleeptalkrecorder.ui.settings

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.time.SleepWindows
import me.mupu.sleeptalkrecorder.ui.format.ClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * When nights and naps begin, set on a bar over the 24 hours of a day: the sun thumb is where naps
 * start, the moon thumb where nights start. Between them lies nap time in a day colour, outside them
 * night time in a night colour, because the night runs over midnight. Pushed together they leave no
 * naps. The thumbs snap to quarter hours while dragged; tapping a thumb or one of the times written
 * below the bar opens Android's time picker for an exact time, which is also how a screen reader
 * sets them.
 *
 * [onChange] gets the new windows once a thumb is let go or a time is picked, and only when they
 * changed; [onRejected] is told when a picked time would put naps after nights.
 */
@Composable
internal fun SleepWindowsSetting(
    @DrawableRes icon: Int,
    windows: SleepWindows,
    onChange: (SleepWindows) -> Unit,
    onRejected: () -> Unit,
) {
    // The bar shows the dragged windows until they are stored and come back as [windows].
    var shown by remember(windows) { mutableStateOf(windows) }
    var picking by rememberSaveable { mutableStateOf<WindowThumb?>(null) }

    Column {
        SettingLabel(icon, stringResource(R.string.settings_sleep_windows))
        Column(Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)) {
            SleepWindowsBar(
                windows = shown,
                onDrag = { shown = it },
                onRelease = { if (shown != windows) onChange(shown) },
                onPick = { picking = it },
            )
            Text(
                windowTimes(shown, LocalClockFormatter.current, onPick = { picking = it }),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                stringResource(R.string.settings_sleep_windows_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    picking?.let { thumb ->
        val time = if (thumb == WindowThumb.NAPS) windows.napsFrom else windows.nightsFrom
        TimePicker(
            time = time,
            onPicked = { picked ->
                val changed = when (thumb) {
                    WindowThumb.NAPS -> windows.withNapsFrom(picked)
                    WindowThumb.NIGHTS -> windows.withNightsFrom(picked)
                }
                when {
                    changed == null -> onRejected()
                    changed != windows -> onChange(changed)
                }
            },
            onClose = { picking = null },
        )
    }
}

/** The thumbs' size; the bar leaves half of it free at each end, so a thumb at midnight fits. */
private val ThumbSize = 40.dp
private val TrackHeight = 14.dp
private val BarHeight = 48.dp
private val LabelHeight = 16.dp

/** The hours the scale under the bar names. */
private val LabelledHours = listOf(0, 6, 12, 18, 24)

/**
 * The bar with its two thumbs and the clock scale under it. A drag anywhere on the bar takes the
 * nearer thumb (see [thumbFor]) and reports every snapped step to [onDrag]; [onRelease] follows when
 * it ends. Tapping a thumb asks to [onPick] its exact time.
 */
@Composable
private fun SleepWindowsBar(
    windows: SleepWindows,
    onDrag: (SleepWindows) -> Unit,
    onRelease: () -> Unit,
    onPick: (WindowThumb) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    // The night is the darker colour in both themes: dark mode's containers are the dark tones.
    val dark = colors.surface.luminance() < 0.5f
    val nightColor = if (dark) colors.primaryContainer else colors.primary
    val dayColor = if (dark) colors.tertiary else colors.tertiaryContainer
    val clock = LocalClockFormatter.current
    val currentWindows by rememberUpdatedState(windows)
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnRelease by rememberUpdatedState(onRelease)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inset = ThumbSize / 2
        val trackWidth = maxWidth - ThumbSize

        /** Where on the bar, from its left edge, a minute of the day lies. */
        fun xOf(minute: Int): Dp = inset + trackWidth * (minute.toFloat() / MINUTES_PER_DAY)

        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(BarHeight)
                    .pointerInput(Unit) {
                        val insetPx = inset.toPx()
                        val trackPx = size.width - 2 * insetPx
                        fun minuteAt(x: Float) = (x - insetPx) / trackPx * MINUTES_PER_DAY

                        var x = 0f
                        var thumb: WindowThumb? = null
                        detectHorizontalDragGestures(
                            onDragStart = { start ->
                                x = start.x
                                thumb = null
                            },
                            onDragEnd = { currentOnRelease() },
                            onDragCancel = { currentOnRelease() },
                        ) { change, amount ->
                            change.consume()
                            // The direction of the first move tells two thumbs at the same time apart.
                            val dragged = thumb ?: currentWindows.thumbFor(minuteAt(x), amount).also { thumb = it }
                            x += amount
                            currentOnDrag(currentWindows.dragged(dragged, minuteAt(x)))
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxWidth().height(TrackHeight).align(Alignment.Center)) {
                    val start = inset.toPx()
                    val width = size.width - 2 * start
                    val naps = start + width * windows.minuteOf(WindowThumb.NAPS) / MINUTES_PER_DAY
                    val nights = start + width * windows.minuteOf(WindowThumb.NIGHTS) / MINUTES_PER_DAY
                    val radius = CornerRadius(size.height / 2)

                    drawRoundRect(nightColor, Offset(start, 0f), Size(width, size.height), radius)
                    if (nights > naps) drawRect(dayColor, Offset(naps, 0f), Size(nights - naps, size.height))
                }
                // The moon comes last, so it lies on top of the sun when both start together; the
                // direction of a drag still reaches either.
                for (thumb in WindowThumb.entries) {
                    val time = if (thumb == WindowThumb.NAPS) windows.napsFrom else windows.nightsFrom
                    Thumb(
                        thumb = thumb,
                        description = stringResource(thumb.description, clock.format(time)),
                        onClick = { onPick(thumb) },
                        modifier = Modifier
                            .align(Alignment.CenterStart)
                            .offset(x = xOf(windows.minuteOf(thumb)) - inset),
                    )
                }
            }

            Box(Modifier.fillMaxWidth().height(LabelHeight)) {
                for (hour in LabelledHours) {
                    Text(
                        clock.formatHour(hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.centeredAt(xOf(hour * 60)),
                    )
                }
            }
        }
    }
}

/** A round thumb with the sun or the moon, announced with the time it sets. */
@Composable
private fun Thumb(thumb: WindowThumb, description: String, onClick: () -> Unit, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = CircleShape,
        color = colors.surfaceContainerHighest,
        contentColor = if (thumb == WindowThumb.NAPS) colors.tertiary else colors.primary,
        shadowElevation = 2.dp,
        modifier = modifier
            .size(ThumbSize)
            .clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_sleep_windows_pick), onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(thumb.icon), contentDescription = null, modifier = Modifier.size(22.dp))
        }
    }
}

/** Places the element so that its horizontal centre lies [x] from the left edge of its parent. */
private fun Modifier.centeredAt(x: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0))
    layout(placeable.width, placeable.height) {
        placeable.place(IntOffset((x.toPx() - placeable.width / 2f).roundToInt(), 0))
    }
}

/**
 * "Naps 12:00 – 18:00 · Nights 18:00 – 12:00" in the user's time format, or the nights alone when
 * the windows leave no naps. Every time is a link that opens the time picker of its thumb. The
 * text's template names the nap start `%1$s` and the night start `%2$s`, in whichever order a
 * language puts them.
 */
@Composable
private fun windowTimes(windows: SleepWindows, clock: ClockFormatter, onPick: (WindowThumb) -> Unit): AnnotatedString {
    val template = stringResource(if (windows.hasNaps) R.string.settings_sleep_windows_times else R.string.settings_sleep_windows_no_naps)
    val linkStyle = TextLinkStyles(
        SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline),
    )
    val currentOnPick by rememberUpdatedState(onPick)

    return buildAnnotatedString {
        var rest = template
        while (rest.isNotEmpty()) {
            val naps = rest.indexOf(NAPS_PLACEHOLDER).let { if (it < 0) Int.MAX_VALUE else it }
            val nights = rest.indexOf(NIGHTS_PLACEHOLDER).let { if (it < 0) Int.MAX_VALUE else it }
            val next = minOf(naps, nights)
            if (next == Int.MAX_VALUE) {
                append(rest)
                break
            }
            append(rest.substring(0, next))
            val thumb = if (next == naps) WindowThumb.NAPS else WindowThumb.NIGHTS
            val time: LocalTime = if (thumb == WindowThumb.NAPS) windows.napsFrom else windows.nightsFrom
            withLink(LinkAnnotation.Clickable(thumb.name, linkStyle) { currentOnPick(thumb) }) {
                append(clock.format(time))
            }
            rest = rest.substring(next + NAPS_PLACEHOLDER.length)
        }
    }
}

// Both placeholders have the same length, which the parsing above relies on.
private const val NAPS_PLACEHOLDER = "%1\$s"
private const val NIGHTS_PLACEHOLDER = "%2\$s"

private val WindowThumb.icon: Int
    get() = when (this) {
        WindowThumb.NAPS -> R.drawable.ic_light_mode
        WindowThumb.NIGHTS -> R.drawable.ic_bedtime
    }

private val WindowThumb.description: Int
    get() = when (this) {
        WindowThumb.NAPS -> R.string.settings_naps_start_at
        WindowThumb.NIGHTS -> R.string.settings_nights_start_at
    }
