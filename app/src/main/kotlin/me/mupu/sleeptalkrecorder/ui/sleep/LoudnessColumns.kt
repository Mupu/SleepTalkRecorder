package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import kotlin.math.ceil

/**
 * What the loudness timeline draws of a [TimelineWindow], in pixels of a plot of the given width
 * and height: one bar per column of [step] pixels, the runs of columns to hatch because they lie
 * in a gap between recordings or where the microphone was unavailable, and the points of the
 * baseline.
 *
 * It is computed once per window and size and then only drawn, so a moving playhead never maps
 * the columns again; mapping works in nanoseconds on both axes and allocates nothing per column.
 */
internal class LoudnessColumns private constructor(
    /** The left edge of each bar. */
    val lefts: FloatArray,
    /** The height of each bar, at least one pixel. */
    val heights: FloatArray,
    /** The index in the events of the event in each bar's stretch; -1 for none, which draws it grey. */
    val events: IntArray,
    /** How many of [lefts], [heights] and [events] hold bars. */
    val bars: Int,
    /** The runs to hatch, as left and right edges one after the other. */
    val hatched: FloatArray,
    /** The baseline as x and y one after the other, y from the plot's top; NaN for y where the line breaks. */
    val baseline: FloatArray,
) {
    companion object {
        /**
         * The columns of [window], a stretch of [timeline]'s clock axis, with [loudness] and the
         * stretches of the [events] (in order) to colour and the stretches of the sleep's audio in which the
         * microphone was [unavailable] (in order of their start); the [baseline] points every
         * [baselineStep] pixels, if wanted.
         */
        fun of(
            timeline: SleepTimeline,
            loudness: SleepLoudness,
            events: List<Stretch>,
            unavailable: List<Stretch>,
            window: TimelineWindow,
            width: Float,
            height: Float,
            step: Float,
            baselineStep: Float?,
        ): LoudnessColumns {
            val columns = if (width > 0 && step > 0) ceil(width / step).toInt() + 2 else 0
            val lefts = FloatArray(columns)
            val heights = FloatArray(columns)
            val eventAt = IntArray(columns)
            var bars = 0
            val hatched = FloatList()
            val points = FloatList()
            if (window.span.isZero || window.span.isNegative) return LoudnessColumns(lefts, heights, eventAt, 0, hatched.toArray(), points.toArray())

            fun heightOf(db: Float) = ((db - loudness.lowDb) / (loudness.highDb - loudness.lowDb)).coerceIn(0f, 1f) * height
            val startNanos = window.start.toNanos()
            val spanMillis = window.span.toMillis()
            fun clockAt(share: Double) = startNanos + (spanMillis * share).toLong() * NANOS_PER_MILLI

            val interrupted = Interruptions(timeline, unavailable)
            var x = 0f
            var nextEvent = 0
            while (x < width) {
                val clockFrom = clockAt(x / width.toDouble())
                val clockUntil = clockAt(((x + step) / width).toDouble().coerceAtMost(1.0))
                if (interrupted.at(clockFrom + (clockUntil - clockFrom) / 2)) {
                    val last = hatched.size - 1
                    if (last > 0 && hatched[last] == x) hatched[last] = x + step else hatched.add(x, x + step)
                    x += step
                    continue
                }
                val from = timeline.positionAtClockNanos(clockFrom)
                val until = timeline.positionAtClockNanos(clockUntil)
                if (until > from && !loudness.isEmpty) {
                    while (nextEvent < events.size && events[nextEvent].end.toNanos() <= from) nextEvent++
                    val event = nextEvent.takeIf { it < events.size && events[it].start.toNanos() < until } ?: -1
                    lefts[bars] = x
                    heights[bars] = heightOf(loudness.loudestInNanos(from, until)).coerceAtLeast(1f)
                    eventAt[bars] = event
                    bars++
                }
                x += step
            }
            for (i in 1 until hatched.size step 2) hatched[i] = hatched[i].coerceAtMost(width)

            if (baselineStep != null && baselineStep > 0) {
                val line = Interruptions(timeline, unavailable)
                var px = 0f
                while (px <= width) {
                    val clock = clockAt(px / width.toDouble())
                    val db = if (line.at(clock)) Float.NaN else loudness.baselineAtNanos(timeline.positionAtClockNanos(clock))
                    points.add(px, if (db.isNaN()) Float.NaN else height - heightOf(db))
                    px += baselineStep
                }
            }
            return LoudnessColumns(lefts, heights, eventAt, bars, hatched.toArray(), points.toArray())
        }

        private const val NANOS_PER_MILLI = 1_000_000L
    }

    /**
     * Whether moments on the clock axis, asked for in increasing order, lie in a gap between
     * recordings or where the microphone was unavailable; one sweep over both lists.
     */
    private class Interruptions(private val timeline: SleepTimeline, private val unavailable: List<Stretch>) {
        private val gaps = timeline.gaps
        private var gap = 0
        private var stretch = 0

        /** The latest end of the unavailable stretches that start at or before the last position asked. */
        private var unavailableUntil = Long.MIN_VALUE

        fun at(clock: Long): Boolean {
            while (gap < gaps.size && gaps[gap].end.toNanos() <= clock) gap++
            if (gap < gaps.size && gaps[gap].start.toNanos() <= clock) return true

            val position = timeline.positionAtClockNanos(clock)
            while (stretch < unavailable.size && unavailable[stretch].start.toNanos() <= position) {
                unavailableUntil = maxOf(unavailableUntil, unavailable[stretch].end.toNanos())
                stretch++
            }
            return position < unavailableUntil
        }
    }

    /** A growing list of floats without boxing. */
    private class FloatList {
        private var values = FloatArray(16)
        var size = 0
            private set

        operator fun get(index: Int) = values[index]

        operator fun set(index: Int, value: Float) {
            values[index] = value
        }

        fun add(first: Float, second: Float) {
            if (size + 2 > values.size) values = values.copyOf(values.size * 2)
            values[size++] = first
            values[size++] = second
        }

        fun toArray(): FloatArray = values.copyOf(size)
    }
}
