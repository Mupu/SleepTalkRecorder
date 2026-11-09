package me.mupu.sleeptalkrecorder.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One bar of a [BarChart]: [events] in the colour of plain events with [speech] in the colour of
 * speech events stacked on top, and the [label] below it, if any.
 */
data class Bar(val events: Float = 0f, val speech: Float = 0f, val label: String? = null)

/**
 * Bars of stacked plain and speech events over a baseline, with grid lines at the top, the middle
 * and the bottom and the top's value, written by [maxLabel], above the top line. The top stands for
 * the highest bar, but for at least [atLeast], so that a chart of zeros still has a scale. With a
 * [highlight] the other bars are drawn paler.
 */
@Composable
fun BarChart(
    bars: List<Bar>,
    highlight: Int?,
    maxLabel: (Float) -> String,
    modifier: Modifier = Modifier,
    atLeast: Float = 1f,
    height: Dp = 140.dp,
) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 10.sp)
    val max = maxOf(bars.maxOfOrNull { it.events + it.speech } ?: 0f, atLeast)
    val maxText = maxLabel(max)

    Canvas(modifier.fillMaxWidth().height(height)) {
        val labelHeight = 16.dp.toPx()
        val top = labelHeight
        val bottom = size.height - labelHeight
        val chart = bottom - top

        for (line in 0..2) {
            val y = top + chart * line / 2
            drawLine(colors.outlineVariant, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }

        if (bars.isEmpty()) return@Canvas
        val slot = size.width / bars.size
        val width = minOf(slot * 0.7f, MAX_BAR_WIDTH.toPx())
        val corner = CornerRadius(minOf(2.dp.toPx(), width / 2))
        var labelsEnd = Float.NEGATIVE_INFINITY
        bars.forEachIndexed { index, bar ->
            val alpha = if (highlight == null || highlight == index) 1f else DIMMED
            val left = index * slot + (slot - width) / 2
            val eventsHeight = bar.events / max * chart
            val speechHeight = bar.speech / max * chart
            if (eventsHeight > 0f) drawRoundRect(colors.primary.copy(alpha = alpha), Offset(left, bottom - eventsHeight), Size(width, eventsHeight), corner)
            if (speechHeight > 0f) {
                drawRoundRect(colors.tertiary.copy(alpha = alpha), Offset(left, bottom - eventsHeight - speechHeight), Size(width, speechHeight), corner)
            }

            // Labels are centred under their bar; one that would touch the label before it is left out.
            val label = bar.label ?: return@forEachIndexed
            val layout = measurer.measure(label, labelStyle)
            val x = (left + width / 2 - layout.size.width / 2).coerceIn(0f, size.width - layout.size.width)
            if (x < labelsEnd + 4.dp.toPx()) return@forEachIndexed
            drawText(layout, topLeft = Offset(x, bottom + 2.dp.toPx()))
            labelsEnd = x + layout.size.width
        }

        // The value of the top grid line, just above it on the left.
        val maxLayout = measurer.measure(maxText, labelStyle)
        drawText(maxLayout, topLeft = Offset(0f, top - maxLayout.size.height - 1.dp.toPx()))
    }
}

/** A ring in the colour of plain events with the [share] (0 to 1) of speech events in theirs, from the top clockwise. */
@Composable
fun Donut(share: Float, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Canvas(modifier) {
        val stroke = size.minDimension * 0.14f
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2, (size.height - diameter) / 2)
        val ring = Size(diameter, diameter)
        drawArc(colors.primary, 0f, 360f, useCenter = false, topLeft = topLeft, size = ring, style = Stroke(stroke))
        drawArc(colors.tertiary, -90f, 360f * share.coerceIn(0f, 1f), useCenter = false, topLeft = topLeft, size = ring, style = Stroke(stroke))
    }
}

/** The opacity of the bars that are not highlighted. */
private const val DIMMED = 0.45f

/** The widest a bar is drawn, so that a chart of two bars does not look like a block. */
private val MAX_BAR_WIDTH = 48.dp
