package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.playback.CutPlan
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
    /** The events of the sleep; empty until they are found ([SleepSummary.eventsFound]). */
    val events: List<SleepEvent> = emptyList(),
    /** The stretches of the sleep's audio in which the microphone was unavailable. */
    val unavailable: List<Stretch> = emptyList(),
    /** The event being played on its own, if any. */
    val playingEventId: Long? = null,
    /** The event at the playhead (see [currentEvent]), which the list highlights and auto zoom frames. */
    val currentEvent: SleepEvent? = null,
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

    /** Whether cut playback is chosen; it is the default, because it is what the app is for. */
    private val cut = MutableStateFlow(true)

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
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Triple<SleepSummary?, List<SleepEvent>, List<Stretch>>(null, emptyList(), emptyList()))

    /**
     * The events the list shows, each with the settings' lead-in and tail as far as they reach
     * without a gap or a taken microphone; equal lists are not published again, so the player
     * only hears of real changes.
     */
    private val listed = combine(sleepAndEvents, timeline, leadIn, tail) { (_, events, unavailable), timeline, leadIn, tail ->
        ListedEvents(
            events,
            events.map { event ->
                if (timeline == null) Stretch(event.start, event.end) else eventWithLeadInAndTail(event, timeline, leadIn, tail, unavailable)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListedEvents(emptyList(), emptyList()))

    /** What cut playback plays; null while full playback is chosen or the audio is still being read. */
    private val cutPlan = combine(listed, timeline, cut) { listed, timeline, cut ->
        if (!cut || timeline == null) null else CutPlan.of(listed.played)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { cutPlan.collect(player::setCutPlan) }
    }

    val state: StateFlow<SleepUiState> = combine(
        sleepAndEvents,
        combine(timeline, loudness, ::Pair),
        combine(player.state, scrubPosition, ::Pair),
        combine(eventPlayed, cut, cutPlan, ::Triple),
        listed,
    ) { (sleep, events, unavailable), (timeline, loudness), (playback, scrub), (played, cut, plan), listed ->
        val position = scrub ?: playback.position
        val player = timeline?.let {
            PlayerUiState(
                duration = it.duration,
                position = position,
                clock = if (it.pieces.isEmpty()) null else it.instantAt(position),
                playing = playback.playing,
                speed = playback.speed,
                failed = playback.failed,
                cut = cut,
                cutIsEmpty = plan?.stretches?.isEmpty() == true,
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
            currentEvent = currentEvent(listed.events, listed.played, position),
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

    /** Plays only the listed events with lead-in and tail ([cut] true) or the whole sleep. */
    fun setCut(cut: Boolean) {
        this.cut.value = cut
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

    /** [eventWithLeadInAndTail] with this sleep's audio and the current settings. */
    private fun withLeadInAndTail(event: SleepEvent): Stretch {
        val timeline = timeline.value ?: return Stretch(event.start, event.end)
        return eventWithLeadInAndTail(event, timeline, leadIn.value, tail.value, unavailable.value)
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

/**
 * The stretch from [leadIn] before [event] to [tail] after it, which ends early at a gap or where
 * the microphone was [unavailable], so that neither is played around the event.
 */
private fun eventWithLeadInAndTail(
    event: SleepEvent,
    timeline: SleepTimeline,
    leadIn: Duration,
    tail: Duration,
    unavailable: List<Stretch>,
): Stretch {
    val wanted = Stretch((event.start - leadIn).coerceAtLeast(Duration.ZERO), event.end + tail)
    return timeline.uninterrupted(wanted.start, wanted.end, Stretch(event.start, event.end), unavailable)
}

/** The events the list shows, and the stretch around each that cut playback plays, in the same order. */
private data class ListedEvents(val events: List<SleepEvent>, val played: List<Stretch>)
