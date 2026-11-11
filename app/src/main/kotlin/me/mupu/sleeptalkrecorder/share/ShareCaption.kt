package me.mupu.sleeptalkrecorder.share

/** What a shared event's caption can name; the user picks them in the share step. */
enum class CaptionItem {
    /** The favourite's name, for an event that has one. */
    NAME,

    /** The local date and clock time the event began. */
    TIME,

    /** The name of the sleep the event is from. */
    SLEEP,

    /** How long the event lasts. */
    LENGTH,

    /** The words recognised in the event, for a speech event with words. */
    TRANSCRIPT,
    ;

    companion object {
        /** The items a caption names until the user changes them; the file shows its length anyway. */
        val DEFAULT: Set<CaptionItem> = setOf(NAME, TIME, TRANSCRIPT)
    }
}

/** The format an event is shared in. */
enum class ShareFormat {
    /**
     * A short video of a still card with the event's audio. Messengers such as WhatsApp show the
     * text sent along with a video as its caption, but drop it with an audio file.
     */
    VIDEO,

    /** The audio alone as an M4A file, without a caption. */
    AUDIO,
}

/**
 * What a caption or card says about one event, each part already written in the app language;
 * null where the event has nothing to say.
 */
data class EventLabels(
    /** The favourite's name. */
    val name: String?,
    /** The name of the event's kind, "Speech" or "Event", which the card shows when it has no [name]. */
    val kind: String,
    /** The local date and clock time it began: "8 Nov, 22:55:41". */
    val time: String,
    /** The words recognised in it, in quotation marks. */
    val transcript: String?,
    val sleep: String,
    val length: String,
)

/**
 * The text sent along with shared events: one line per event naming the chosen [items] that it
 * has, in the order of [CaptionItem]; null when no line names anything.
 */
fun caption(events: List<EventLabels>, items: Set<CaptionItem>): String? =
    events.map { captionLine(it, items) }.filter { it.isNotEmpty() }.joinToString("\n").ifEmpty { null }

/** One event's line of a [caption], its parts separated by a middle dot. */
internal fun captionLine(event: EventLabels, items: Set<CaptionItem>): String =
    CaptionItem.entries.filter { it in items }.mapNotNull { item ->
        when (item) {
            CaptionItem.NAME -> event.name
            CaptionItem.TIME -> event.time
            CaptionItem.SLEEP -> event.sleep
            CaptionItem.LENGTH -> event.length
            CaptionItem.TRANSCRIPT -> event.transcript
        }?.takeIf { it.isNotBlank() }
    }.joinToString(" · ")
