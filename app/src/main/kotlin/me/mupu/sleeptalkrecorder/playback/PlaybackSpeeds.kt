package me.mupu.sleeptalkrecorder.playback

/** What the player plays, which decides the speed it plays at. */
enum class PlaybackKind {
    /** The sleep, in full or cut playback. */
    SLEEP,

    /** One stretch on its own, such as a single event with its lead-in and tail. */
    EVENT,
}

/**
 * The playing speeds, kept apart for the sleep and for single events, so speeding through a sleep
 * never makes the next event played on its own hard to follow. Each is a multiple of the recorded
 * speed from [STEPS]; neither is stored, so both start at 1 again with every player.
 */
data class PlaybackSpeeds(val sleep: Float = 1f, val event: Float = 1f) {

    /** The speed [kind] plays at. */
    fun of(kind: PlaybackKind): Float = when (kind) {
        PlaybackKind.SLEEP -> sleep
        PlaybackKind.EVENT -> event
    }

    /** These speeds with the one of [kind] moved to the next of [STEPS], after the fastest back to the slowest. */
    fun next(kind: PlaybackKind): PlaybackSpeeds {
        val current = STEPS.indexOfFirst { it >= of(kind) }
        val next = STEPS[(current + 1) % STEPS.size]
        return when (kind) {
            PlaybackKind.SLEEP -> copy(sleep = next)
            PlaybackKind.EVENT -> copy(event = next)
        }
    }

    companion object {
        /** The speeds a tap on the speed button steps through. */
        val STEPS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
    }
}
