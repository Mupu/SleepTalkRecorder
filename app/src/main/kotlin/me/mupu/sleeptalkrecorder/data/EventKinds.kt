package me.mupu.sleeptalkrecorder.data

/** The event kinds the app ships; the database adds those it does not hold yet each time it opens. */
object EventKinds {

    /**
     * A noise: louder than the baseline, with no words or murmur heard in it, either because speech
     * recognition found none or because it has not run yet. Its own system tag filters it.
     * The id is `event`, the name this kind had before murmurs became a kind of their own.
     */
    const val NOISE = "event"

    /**
     * A murmur: speech recognition heard only fillers such as "mhm" or "uh", a voice without words.
     * It has the theme's secondary colour, between noises and speech, and its own system tag.
     */
    const val MURMUR = "murmur"

    /**
     * A speech event: speech recognition recognised words in it, or the user marked it as speech. It
     * has the theme's tertiary colour, which sets it apart from noises in the primary colour, and
     * its own system tag in the tag filter.
     */
    const val SPEECH = "speech"

    /** Whether [kindId] is a kind in which a voice was heard: speech or a murmur. */
    fun isVoice(kindId: String): Boolean = kindId == SPEECH || kindId == MURMUR

    /** The kinds the app ships, unnamed so the app names them in its language. */
    val builtIn: List<EventKindEntity> = listOf(
        EventKindEntity(id = NOISE, name = null, color = KindColor.Theme(ThemeRole.PRIMARY), hasFilter = true),
        EventKindEntity(id = MURMUR, name = null, color = KindColor.Theme(ThemeRole.SECONDARY), hasFilter = true),
        EventKindEntity(id = SPEECH, name = null, color = KindColor.Theme(ThemeRole.TERTIARY), hasFilter = true),
    )
}
