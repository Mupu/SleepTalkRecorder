package me.mupu.sleeptalkrecorder.data

/** The event kinds the app ships; the database adds those it does not hold yet each time it opens. */
object EventKinds {

    /** A plain event: louder than the baseline, with nothing known about what made the sound. */
    const val EVENT = "event"

    /**
     * A speech event: speech recognition heard words or a wordless murmur in it. It has the
     * theme's tertiary colour, which sets it apart from plain events in the primary colour, and
     * its own filter on the sleep screen.
     */
    const val SPEECH = "speech"

    /** The kinds the app ships, unnamed so the app names them in its language. */
    val builtIn: List<EventKindEntity> = listOf(
        EventKindEntity(id = EVENT, name = null, color = KindColor.Theme(ThemeRole.PRIMARY), hasFilter = false),
        EventKindEntity(id = SPEECH, name = null, color = KindColor.Theme(ThemeRole.TERTIARY), hasFilter = true),
    )
}
