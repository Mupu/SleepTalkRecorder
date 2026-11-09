package me.mupu.sleeptalkrecorder.ui.sleep

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.playback.PlaybackKind
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
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
    val filter: EventFilter = EventFilter.All,
    /** The events the list shows and cut playback plays: those the [filter] shows. */
    val listedEvents: List<SleepEvent> = emptyList(),
    /** The stretches of the sleep's audio in which the microphone was unavailable. */
    val unavailable: List<Stretch> = emptyList(),
    /** The event being played on its own, if any. */
    val playingEventId: Long? = null,
    /** The event at the playhead (see [currentEvent]), which the list highlights and auto zoom frames. */
    val currentEvent: SleepEvent? = null,
    /** Whether the sensitivity can be set: the sleep is finished, its events found and its loudness read. */
    val sensitivityAvailable: Boolean = false,
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

/** The user said [event] is not speech; [previous] are the user's transcripts over it before, which undo restores. */
class NotSpeech(val event: SleepEvent, val previous: List<AnnotationEntity>)
