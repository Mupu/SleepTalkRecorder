package me.mupu.sleeptalkrecorder.share

/** What a shared event's caption can name; the user picks them in the share step. */
enum class CaptionItem {
    /** The name the user gave the event, for an event that has one. */
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
    /** The name the user gave the event. */
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
 * has, in the order of [CaptionItem], each starting with the event's [shareNumber] when several
 * are shared; null when no line names anything. A line that names nothing is left out, and the
 * others keep the numbers of their events.
 */
fun caption(events: List<EventLabels>, items: Set<CaptionItem>): String? =
    events.mapIndexedNotNull { index, event ->
        captionLine(event, items).takeIf { it.isNotEmpty() }?.let { line ->
            shareNumber(index, events.size)?.let { "$it. $line" } ?: line
        }
    }.joinToString("\n").ifEmpty { null }

/**
 * The number of the event at [index] of [count] shared together, from 1, which its caption line,
 * its card and its file name carry so that they can be matched; null for an event shared alone.
 */
fun shareNumber(index: Int, count: Int): Int? = if (count > 1) index + 1 else null

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
