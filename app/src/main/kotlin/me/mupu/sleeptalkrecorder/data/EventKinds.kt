package me.mupu.sleeptalkrecorder.data

/** The event kinds the app ships; the database adds those it does not hold yet each time it opens. */
object EventKinds {

    /** A plain event: louder than the baseline, with nothing known about what made the sound. */
    const val EVENT = "event"

    /** The kinds the app ships, unnamed so the app names them in its language. */
    val builtIn: List<EventKindEntity> = listOf(
        EventKindEntity(id = EVENT, name = null, color = "primary", hasFilter = false),
    )
}
