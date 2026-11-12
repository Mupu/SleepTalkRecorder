package me.mupu.sleeptalkrecorder.detection

import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.HOP_MILLIS
import java.time.Duration
import kotlin.math.max
import kotlin.math.min

/**
 * Tells sounds that start suddenly from those that swell slowly, on the smoothed speech-band
 * [Detection.level].
 *
 * Speech, coughs and most snores rise by 10 to 30 dB within a fraction of a second. A car passing
 * outside swells by at most 2 to 11 dB per second, about 5 dB in half a second, and a fan or an air
 * conditioner that speeds up or rain that sets in even slower. A stretch is sudden when the level
 * rises by at least [minRiseDb] within [riseWindow] at some hop from [searchBefore] its start until
 * [searchAfter] it (but not past its end). The defaults, 9 dB within 500 ms, were the best trade
 * measured on the labelled test nights.
 */
data class OnsetRule(
    /** The rise a sudden stretch reaches, in dB. */
    val minRiseDb: Float = 9f,
    /** The time the rise must happen within. */
    val riseWindow: Duration = Duration.ofMillis(500),
    /** How long before a stretch's first hop its rise may end: the level is smoothed over 100 ms. */
    val searchBefore: Duration = Duration.ofMillis(100),
    /** How long after a stretch's first hop its rise may end. */
    val searchAfter: Duration = Duration.ofMillis(500),
) {

    /** Whether [stretch] starts suddenly enough on [level]. */
    fun isSudden(level: FloatArray, stretch: HopRange): Boolean = rise(level, stretch) >= minRiseDb

    /**
     * The largest rise of [level] within [riseWindow] that ends at a hop from [searchBefore] before
     * [stretch]'s start until [searchAfter] after it, held within the stretch: the level at that
     * hop minus the lowest level in the window before it, in dB.
     */
    fun rise(level: FloatArray, stretch: HopRange): Float {
        val window = max(1, hops(riseWindow))
        val from = max(1, stretch.start - hops(searchBefore))
        val until = min(min(level.size, stretch.end), max(stretch.start + hops(searchAfter), from + 1))
        var best = 0f
        for (hop in from until until) {
            var lowest = level[hop - 1]
            for (before in max(0, hop - window) until hop - 1) lowest = min(lowest, level[before])
            best = max(best, level[hop] - lowest)
        }
        return best
    }

    private fun hops(duration: Duration): Int = (duration.toMillis() / HOP_MILLIS).toInt()
}
