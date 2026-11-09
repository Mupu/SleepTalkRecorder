package me.mupu.sleeptalkrecorder.detection

import java.time.Duration

/**
 * The sensitivity the user set for the stretch of a sleep's audio from [start] until [end]: the
 * start threshold there, in dB above the baseline, in place of the automatic one.
 *
 * It names positions in the audio, not hops or events, so every new search for events uses it
 * again, also one by a changed detector. A setting for the whole sleep is one that covers it.
 */
data class SensitivityOverride(
    val start: Duration,
    val end: Duration,
    val thresholdDb: Float,
    /** The stored annotation's id; ids grow, so a newer override has the larger one. */
    val id: Long = 0,
) {
    /** Whether the override lies wholly within [start] until [end]. */
    fun liesWithin(start: Duration, end: Duration): Boolean = this.start >= start && this.end <= end
}

/**
 * The overrides (oldest first) that a new setting for [start] until [end] replaces: those that lie
 * within it, which it would hide completely. Overrides reaching out of the stretch stay, and the
 * new one counts over them where they overlap.
 */
fun List<SensitivityOverride>.replacedBy(start: Duration, end: Duration): List<SensitivityOverride> =
    filter { it.liesWithin(start, end) }
