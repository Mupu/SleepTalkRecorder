package me.mupu.sleeptalkrecorder.playback

/**
 * The volume boosts, kept apart for the sleep and for single events like the [PlaybackSpeeds], so
 * a boost for whispered events never makes the sleep loud. Each is a gain from [STEPS], 1 being
 * off; neither is stored, so both start off again with every player. Shares are never boosted.
 */
data class PlaybackBoosts(val sleep: Int = 1, val event: Int = 1) {

    /** The boost [kind] plays with. */
    fun of(kind: PlaybackKind): Int = when (kind) {
        PlaybackKind.SLEEP -> sleep
        PlaybackKind.EVENT -> event
    }

    /** These boosts with the one of [kind] moved to the next of [STEPS], after the strongest back to off. */
    fun next(kind: PlaybackKind): PlaybackBoosts {
        val current = STEPS.indexOfFirst { it >= of(kind) }
        val next = STEPS[(current + 1) % STEPS.size]
        return when (kind) {
            PlaybackKind.SLEEP -> copy(sleep = next)
            PlaybackKind.EVENT -> copy(event = next)
        }
    }

    companion object {
        /** The gains a tap on the boost button steps through; 1 is off. */
        val STEPS = listOf(1, 2, 4, 8)
    }
}

