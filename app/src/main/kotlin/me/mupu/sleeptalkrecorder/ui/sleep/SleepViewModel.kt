package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.playback.SleepPlayer
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The player of the sleep screen, once the sleep's audio is known. */
data class PlayerUiState(
    /** How long the whole sleep plays; zero if it has no playable audio. */
    val duration: Duration,
    /** The position shown: the playing position, or where the timeline is being dragged to. */
    val position: Duration,
    /** The instant the sample at [position] was recorded; null without audio. */
    val clock: Instant?,
    val playing: Boolean,
    val speed: Float,
    val failed: Boolean,
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
    /** The events of the sleep; empty until they are found ([SleepSummary.eventsFound]). */
    val events: List<SleepEvent> = emptyList(),
    /** The stretches of the sleep's audio in which the microphone was unavailable. */
    val unavailable: List<Stretch> = emptyList(),
    /** The event being played on its own, if any. */
    val playingEventId: Long? = null,
)

/** State and actions of the sleep screen: the sleep, its events, and their playback. */
class SleepViewModel(
    sleepId: Long,
    repository: SleepRepository,
    settings: SettingsStore,
    private val player: SleepPlayer,
) : ViewModel() {

    private val timeline = MutableStateFlow<SleepTimeline?>(null)
    private val loudness = MutableStateFlow<SleepLoudness?>(null)

    /** Where the user is dragging the timeline to; null when not dragging. */
    private val scrubPosition = MutableStateFlow<Duration?>(null)

    /** The event last played on its own; it is playing while the player plays a stretch. */
    private val eventPlayed = MutableStateFlow<Long?>(null)

    private val leadIn = settings.flow(Settings.leadIn).stateIn(viewModelScope, SharingStarted.Eagerly, Settings.leadIn.default)
    private val tail = settings.flow(Settings.tail).stateIn(viewModelScope, SharingStarted.Eagerly, Settings.tail.default)
    private val unavailable = repository.microphoneUnavailable(sleepId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    init {
        viewModelScope.launch {
            val loaded = repository.timeline(sleepId)
            player.load(loaded)
            timeline.value = loaded

            val track = repository.track(sleepId)
            val detection = withContext(Dispatchers.Default) { EventDetector.detect(track.track) }
            loudness.value = SleepLoudness(track, detection.level, detection.baseline)
        }
    }

    private val sleepAndEvents = combine(repository.sleep(sleepId), repository.events(sleepId), unavailable) { sleep, events, unavailable ->
        Triple(sleep, if (sleep?.eventsFound == true) events else emptyList(), unavailable)
    }

    val state: StateFlow<SleepUiState> = combine(
        sleepAndEvents,
        combine(timeline, loudness, ::Pair),
        player.state,
        scrubPosition,
        eventPlayed,
    ) { (sleep, events, unavailable), (timeline, loudness), playback, scrub, played ->
        val player = timeline?.let {
            val position = scrub ?: playback.position
            PlayerUiState(
                duration = it.duration,
                position = position,
                clock = if (it.pieces.isEmpty()) null else it.instantAt(position),
                playing = playback.playing,
                speed = playback.speed,
                failed = playback.failed,
            )
        }
        SleepUiState(
            sleep = sleep,
            timeline = timeline,
            player = player,
            loudness = loudness,
            events = events,
            unavailable = unavailable,
            playingEventId = played.takeIf { playback.playing && playback.until != null },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepUiState())

    fun togglePlay() {
        if (player.state.value.playing) player.pause() else player.play()
    }

    fun pause() {
        player.pause()
    }

    /** Moves the playback to [position] straight away, as a tap on the timeline does. */
    fun seekTo(position: Duration) {
        player.seekTo(position)
    }

    /** Shows [position] while the timeline is dragged, without moving the playback yet. */
    fun scrub(position: Duration) {
        scrubPosition.value = position
    }

    /** Moves the playback to where the timeline was dragged to. */
    fun finishScrub() {
        val position = scrubPosition.value ?: return
        player.seekTo(position)
        scrubPosition.value = null
    }

    /** Moves the playback to just before [event], with the lead-in of the settings. */
    fun seekToEvent(event: SleepEvent) {
        player.seekTo(withLeadInAndTail(event).start)
    }

    /** Plays [event] on its own with the settings' lead-in and tail, or pauses it while it plays. */
    fun togglePlayEvent(event: SleepEvent) {
        if (state.value.playingEventId == event.id) {
            player.pause()
            return
        }
        eventPlayed.value = event.id
        val stretch = withLeadInAndTail(event)
        player.playRange(stretch.start, stretch.end)
    }

    /**
     * The stretch from the lead-in before [event] to the tail after it, which ends early at a gap
     * or where the microphone was unavailable, so that neither is played around the event.
     */
    private fun withLeadInAndTail(event: SleepEvent): Stretch {
        val wanted = Stretch((event.start - leadIn.value).coerceAtLeast(Duration.ZERO), event.end + tail.value)
        return timeline.value?.uninterrupted(wanted.start, wanted.end, Stretch(event.start, event.end), unavailable.value) ?: wanted
    }

    /** Switches to the next speed, after the fastest back to the slowest. */
    fun nextSpeed() {
        val current = SPEEDS.indexOfFirst { it >= player.state.value.speed }
        player.setSpeed(SPEEDS[(current + 1) % SPEEDS.size])
    }

    override fun onCleared() {
        player.release()
    }

    private companion object {
        /** The speeds the speed button cycles through; they apply only until the screen closes. */
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
    }
}
