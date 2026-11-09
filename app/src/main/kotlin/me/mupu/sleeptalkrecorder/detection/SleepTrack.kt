package me.mupu.sleeptalkrecorder.detection

import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.HOP_MILLIS
import java.time.Duration

/**
 * The feature tracks of a sleep's recordings back to back as one [track], with the position in the
 * sleep's audio of each hop. A recording rarely ends on a whole hop, so the next recording's hops
 * start up to 20 ms later than their index suggests; [positionOf] and [hopAt] account for that, and
 * positions therefore stay exact over any number of recordings.
 */
class SleepTrack private constructor(
    /** All hops of the sleep. */
    val track: FeatureTrack,
    private val firstHops: IntArray,
    private val starts: List<Duration>,
) {

    /** The position in the sleep's audio where [hop] starts; [FeatureTrack.size] gives the end of the last hop. */
    fun positionOf(hop: Int): Duration {
        val recording = recordingOf(hop)
        return starts[recording] + Duration.ofMillis((hop - firstHops[recording]) * HOP_MILLIS)
    }

    /** [starts] in nanoseconds, for [hopAtNanos]. */
    private val startNanos = LongArray(starts.size) { starts[it].toNanos() }

    /** The hop that holds [position], held within the track. */
    fun hopAt(position: Duration): Int = hopAtNanos(position.toNanos())

    /** [hopAt] for a position in nanoseconds, without allocating: the timeline maps every column with it. */
    fun hopAtNanos(position: Long): Int {
        if (track.size == 0) return 0
        var recording = startNanos.size - 1
        while (recording > 0 && startNanos[recording] > position) recording--
        val first = firstHops[recording]
        val until = if (recording < firstHops.lastIndex) firstHops[recording + 1] else track.size
        val into = Math.floorDiv(position - startNanos[recording], NANOS_PER_MILLI).coerceAtLeast(0) / HOP_MILLIS
        // A position past a recording's last hop (or in a recording without hops) is its last hop.
        return (first + into).toInt().coerceAtMost(until - 1).coerceIn(0, track.size - 1)
    }

    /** The hops that hold the audio from [start] until [end], held within the track. */
    fun hopsOf(start: Duration, end: Duration): HopRange {
        val first = hopAt(start)
        return if (end <= start) HopRange(first, first) else HopRange(first, hopAt(end - Duration.ofNanos(1)) + 1)
    }

    /** Finds the events of the sleep with the user's [overrides] (oldest first). */
    fun detect(overrides: List<SensitivityOverride>, params: DetectionParams = DetectionParams()): Detection =
        EventDetector.detect(track, params, thresholdOverrides(overrides))

    /** [overrides] (oldest first) on the hops of this track. */
    fun thresholdOverrides(overrides: List<SensitivityOverride>): List<ThresholdOverride> =
        overrides.map { ThresholdOverride(hopsOf(it.start, it.end), it.thresholdDb) }

    /** The recording that holds [hop]; on a boundary the later one, which starts with it. */
    private fun recordingOf(hop: Int): Int = firstHops.indexOfLast { it <= hop }.coerceAtLeast(0)

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000L

        /** The sleep of [recordings]: each recording's track and the position of its first sample. */
        fun of(recordings: List<Pair<FeatureTrack, Duration>>): SleepTrack {
            val firstHops = IntArray(recordings.size)
            var hops = 0
            recordings.forEachIndexed { index, (track, _) ->
                firstHops[index] = hops
                hops += track.size
            }
            val starts = recordings.map { it.second }.ifEmpty { listOf(Duration.ZERO) }
            return SleepTrack(FeatureTrack.concat(recordings.map { it.first }), firstHops.takeIf { it.isNotEmpty() } ?: IntArray(1), starts)
        }
    }
}
