package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs

/**
 * Detects the gestures of the zoomable timeline, which moves like a map under the fingers. All
 * positions are x coordinates in pixels of the timeline.
 *
 * - [onDown] on every touch, so a running fling or zoom stops where the finger lands.
 * - [onMove] while one finger drags or two fingers pinch: the point at `from` is now at `to`, and
 *   the timeline is `zoom` times as detailed (above 1 zooms in). A pinch zooms around the point
 *   between the fingers and pans with it at the same time.
 * - [onFling] with the horizontal speed in pixels per second when the fingers leave after moving.
 * - [onTap] for a single tap, once no second tap followed it; [onDoubleTap] for a double tap.
 *
 * The timeline takes a gesture only once it moves sideways or pinches: a finger that first moves
 * up or down is left unconsumed for whatever scrolls around the timeline.
 */
internal suspend fun PointerInputScope.detectTimelineGestures(
    onDown: () -> Unit,
    onMove: (from: Float, to: Float, zoom: Float) -> Unit,
    onFling: (velocity: Float) -> Unit,
    onTap: (x: Float) -> Unit,
    onDoubleTap: (x: Float) -> Unit,
) {
    awaitEachGesture {
        val first = awaitFirstDown(requireUnconsumed = false)
        onDown()
        if (follow(first, onMove, onFling) != Outcome.Tapped) return@awaitEachGesture

        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) { awaitFirstDown(requireUnconsumed = false) }
        if (second == null) {
            onTap(first.position.x)
            return@awaitEachGesture
        }
        onDown()
        // A second touch that drags instead of tapping is a drag, and the tap before it is dropped.
        if (follow(second, onMove, onFling) == Outcome.Tapped) onDoubleTap(second.position.x)
    }
}

/** How a touch that went down ended. */
private enum class Outcome { Tapped, Moved, PassedOn }

/** Follows the touch that started with [down] until every finger is up, moving and flinging the timeline once it took it. */
private suspend fun AwaitPointerEventScope.follow(
    down: PointerInputChange,
    onMove: (from: Float, to: Float, zoom: Float) -> Unit,
    onFling: (velocity: Float) -> Unit,
): Outcome {
    val slop = viewConfiguration.touchSlop
    // The velocity follows the summed sideways movement of the fingers rather than one finger,
    // so a finger that joins or leaves a pinch does not look like a jump.
    val velocity = VelocityTracker()
    var travelled = 0f
    velocity.addPosition(down.uptimeMillis, Offset.Zero)

    var taken = false
    var panSoFar = Offset.Zero
    var zoomSoFar = 1f
    while (true) {
        val event = awaitPointerEvent()
        if (event.changes.none { it.pressed }) break

        val zoom = event.calculateZoom()
        val pan = event.calculatePan()
        travelled += pan.x
        velocity.addPosition(event.changes.first().uptimeMillis, Offset(travelled, 0f))

        if (!taken) {
            if (event.changes.any { it.isConsumed }) return Outcome.PassedOn
            panSoFar += pan
            zoomSoFar *= zoom
            val pinched = event.calculateCentroidSize(useCurrent = false) * abs(1 - zoomSoFar) > slop
            val sideways = abs(panSoFar.x) > slop && abs(panSoFar.x) > abs(panSoFar.y)
            when {
                pinched || sideways -> taken = true
                abs(panSoFar.y) > slop -> return Outcome.PassedOn
            }
        } else {
            val centroid = event.calculateCentroid(useCurrent = true)
            if (centroid.isSpecified && (zoom != 1f || pan.x != 0f)) onMove(centroid.x - pan.x, centroid.x, zoom)
        }
        if (taken) event.changes.forEach { if (it.positionChanged()) it.consume() }
    }
    if (!taken) return Outcome.Tapped

    val maximum = viewConfiguration.maximumFlingVelocity
    onFling(velocity.calculateVelocity(Velocity(maximum, maximum)).x)
    return Outcome.Moved
}
