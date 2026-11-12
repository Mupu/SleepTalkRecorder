package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.data.EventTag
import me.mupu.sleeptalkrecorder.data.EventWay
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.playback.PlaybackKind
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.transcription.NewWayProgress
import me.mupu.sleeptalkrecorder.ui.tags.TagFilter
import java.time.Duration

/** The player of the sleep screen, once the sleep's audio is known; its position is [SleepViewModel.position]. */
data class PlayerUiState(
    /** How long the whole sleep plays; zero if it has no playable audio. */
    val duration: Duration,
    val playing: Boolean,
    /** The speed of what plays now; see [speedOf]. */
    val speed: Float,
    /** Whether [speed] is that of the sleep or that of single events, which are kept apart. */
    val speedOf: PlaybackKind,
    val failed: Boolean,
    /** Whether only the listed events are played (cut playback) rather than the whole sleep. */
    val cut: Boolean,
    /** Whether cut playback is chosen but no event is listed, so it has nothing to play. */
    val cutIsEmpty: Boolean,
) {
    /** Whether there is anything to play. */
    val hasAudio: Boolean get() = duration > Duration.ZERO
}

/** Everything the sleep screen shows. */
data class SleepUiState(
    /** The sleep, or null while loading or once it is gone. */
    val sleep: SleepSummary? = null,
    /** The sleep's audio; null while it is being read. */
    val timeline: SleepTimeline? = null,
    /** Null while the sleep's audio is being read. */
    val player: PlayerUiState? = null,
    /** The loudness the timeline draws; null while it is being read. */
    val loudness: SleepLoudness? = null,
    /** The events of the sleep, hidden ones too; empty until they are found ([SleepSummary.eventsFound]). */
    val events: List<SleepEvent> = emptyList(),
    /** The events the timeline colours: those that are not hidden. */
    val timelineEvents: List<SleepEvent> = emptyList(),
    /** Which events the list shows. */
    val filter: TagFilter = TagFilter(),
    /** The events the list shows and cut playback plays: those the [filter] shows. */
    val listedEvents: List<SleepEvent> = emptyList(),
    /** The stretches of the sleep's audio in which the microphone was unavailable. */
    val unavailable: List<Stretch> = emptyList(),
    /** The event being played on its own, if any. */
    val playingEventId: Long? = null,
    /** The event at the playhead (see [currentEvent]), which the list highlights and auto zoom frames. */
    val currentEvent: SleepEvent? = null,
    /** The listed event the player steps back to (see [neighbourEvents]); null if there is none. */
    val previousEvent: SleepEvent? = null,
    /** The listed event the player steps on to; null if there is none. */
    val nextEvent: SleepEvent? = null,
    /**
     * Whether the sensitivity can be set: the sleep is finished, its events found and its loudness
     * read, and the first way's events are shown, which the sensitivity belongs to.
     */
    val sensitivityAvailable: Boolean = false,
    /** The events selected together, in order; selection mode while there are any. */
    val selected: List<SleepEvent> = emptyList(),
    /** Which way's events are shown. */
    val way: EventWay = EventWay.OLD,
    /** Whether developer mode is on: events show what the second way knows, and the sleep can be searched again. */
    val developerMode: Boolean = false,
    /** Where the second way stands for this sleep while its events are shown; null while the first way's are. */
    val newWay: NewWayState? = null,
    /** The system tags the filter offers beyond the kinds, named and hidden, for the way shown. */
    val extraTags: List<EventTag.System> = emptyList(),
) {
    /**
     * How many events the sleep has that are neither hidden nor background with the way shown, and
     * how long they last together; null for the first way, whose numbers the sleep itself carries.
     */
    val wayTotals: Pair<Int, Duration>? get() = if (way == EventWay.OLD) null else events
        .filter { !it.hidden && !it.background }
        .let { shown -> shown.size to shown.fold(Duration.ZERO) { sum, event -> sum + (event.end - event.start) } }
}

/** Where the second way of finding events stands for a sleep. */
data class NewWayState(
    /** Whether it searched the sleep to its end; the events of the parts searched so far are shown before. */
    val searched: Boolean,
    /** Whether a sound model it needs is not installed, so it cannot search. */
    val modelsMissing: Boolean,
    /** Its search or speech recognition while it works on the sleep; null otherwise. */
    val progress: NewWayProgress? = null,
    /** Whether the sleep's audio could not be read to its end, so it was searched only in part. */
    val partial: Boolean = false,
)

/** The sensitivity sheet while it is open, for the whole sleep or around one event. */
data class SensitivityUiState(
    /** The event the sheet was opened from; null for the whole sleep. */
    val event: SleepEvent?,
    /** The stretch of the sleep's audio and the settings it is shown with. */
    val editor: SensitivityEditor,
    /** The start threshold the slider shows, in dB above the baseline. */
    val thresholdDb: Float,
    /** Whether the user changed the setting, so that it can be applied. */
    val changed: Boolean,
    /** How many events the stretch held when the sheet opened. */
    val before: Int,
    /** The stretch re-scanned with the slider's setting; null until the first re-scan is done. */
    val chart: SensitivityChart?,
    /** Whether the setting is being stored and the sleep searched again. */
    val applying: Boolean,
)

/**
 * A sensitivity was applied and the sleep searched again: [events] now lie in the stretch, which
 * is the whole sleep if [wholeSleep].
 */
data class SensitivityApplied(val wholeSleep: Boolean, val events: Int)

/**
 * The user marked [event] as speech ([speech] true) or as not speech; [previous] are the user's
 * transcripts over it before, which undo restores.
 */
class SpeechMarked(val event: SleepEvent, val speech: Boolean, val previous: List<AnnotationEntity>)
