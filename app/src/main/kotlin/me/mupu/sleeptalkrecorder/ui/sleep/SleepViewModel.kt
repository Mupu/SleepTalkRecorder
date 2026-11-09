package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.EventKindEntity
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.detection.Detection
import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.detection.SleepTrack
import me.mupu.sleeptalkrecorder.playback.CutPlan
import me.mupu.sleeptalkrecorder.playback.SleepPlayer
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.transcription.TranscriptionStatus
import me.mupu.sleeptalkrecorder.transcription.Transcriptions
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
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
    /** The kind the list is filtered to, such as speech; null for all events. */
    val filter: String? = null,
    /** The events the list shows and cut playback plays: those of the [filter]'s kind, or all. */
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

/** State and actions of the sleep screen: the sleep, its events, their playback and speech recognition. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepViewModel(
    private val sleepId: Long,
    private val repository: SleepRepository,
    settings: SettingsStore,
    private val player: SleepPlayer,
    private val transcriptions: Transcriptions,
) : ViewModel() {

    private val timeline = MutableStateFlow<SleepTimeline?>(null)
    private val loudness = MutableStateFlow<SleepLoudness?>(null)

    /** The sleep's feature track and its curves, which the sensitivity sheet re-scans; null while being read. */
    private val analysis = MutableStateFlow<Pair<SleepTrack, Detection>?>(null)

    /** The kind the list is filtered to; null for all events. */
    private val filter = MutableStateFlow<String?>(null)

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
            analysis.value = track to detection
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
    private val listed = combine(sleepAndEvents, timeline, leadIn, tail, filter) { (_, all, unavailable), timeline, leadIn, tail, filter ->
        val events = if (filter == null) all else all.filter { it.kindId == filter }
        ListedEvents(
            filter,
            events,
            events.map { event ->
                if (timeline == null) Stretch(event.start, event.end) else eventWithLeadInAndTail(event, timeline, leadIn, tail, unavailable)
            },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ListedEvents(null, emptyList(), emptyList()))

    /** What cut playback plays; null while full playback is chosen or the audio is still being read. */
    private val cutPlan = combine(listed, timeline, cut) { listed, timeline, cut ->
        if (!cut || timeline == null) null else CutPlan.of(listed.played)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { cutPlan.collect(player::setCutPlan) }
    }

    /** Where speech recognition stands for the sleep; it starts once the events are found. */
    val transcription: StateFlow<TranscriptionStatus> = combine(
        transcriptions.status(sleepId, repository.transcriptionCount(sleepId)),
        repository.sleep(sleepId),
    ) { status, sleep -> if (sleep?.eventsFound == true) status else TranscriptionStatus.NoEvents }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptionStatus.NoEvents)

    /** The event kinds the event list can be filtered by. */
    val filterKinds: StateFlow<List<EventKindEntity>> =
        repository.filterKinds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
            filter = listed.filter,
            listedEvents = listed.events,
            unavailable = unavailable,
            playingEventId = played.takeIf { playback.playing && playback.until != null },
            currentEvent = currentEvent(listed.events, listed.played, position),
            sensitivityAvailable = loudness != null && sleep?.eventsFound == true && !sleep.isRunning,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepUiState())

    private val overrides = repository.sensitivityOverrides(sleepId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The open sensitivity sheet; null while it is closed. */
    private val sheet = MutableStateFlow<Sheet?>(null)

    /** The sheet's stretch re-scanned with its setting, off the main thread; a newer setting cancels an older re-scan. */
    private val chart = sheet
        .map { open -> open?.let { it.editor to it.setting } }
        .distinctUntilChanged()
        .mapLatest { request ->
            request?.let { (editor, setting) -> editor to withContext(Dispatchers.Default) { editor.chart(setting, CHART_COLUMNS) } }
        }

    /** The sensitivity sheet; null while it is closed. */
    val sensitivity: StateFlow<SensitivityUiState?> = combine(sheet, chart) { open, chart ->
        open?.let {
            SensitivityUiState(
                event = it.event,
                editor = it.editor,
                thresholdDb = when (val setting = it.setting) {
                    SensitivitySetting.Unchanged -> it.editor.currentDb
                    is SensitivitySetting.Threshold -> setting.db
                    SensitivitySetting.Inherited -> it.editor.inheritedDb
                },
                changed = it.setting != SensitivitySetting.Unchanged,
                before = it.before,
                // The chart of a sheet opened earlier is never shown.
                chart = chart?.takeIf { (editor, _) -> editor === it.editor }?.second,
                applying = it.applying,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val applied = Channel<SensitivityApplied>(Channel.BUFFERED)

    /** Each sensitivity applied, once the sleep was searched again with it. */
    val sensitivityApplied: Flow<SensitivityApplied> = applied.receiveAsFlow()

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

    /** Lists and plays only the events of the kind [kindId], or all of them for null. */
    fun setFilter(kindId: String?) {
        filter.value = kindId
    }

    /** Recognises speech in every event of the sleep again, now, whatever the settings say. */
    fun runSpeechRecognition() = transcriptions.runNow(sleepId)

    /** Switches to the next speed, after the fastest back to the slowest. */
    fun nextSpeed() {
        val current = SPEEDS.indexOfFirst { it >= player.state.value.speed }
        player.setSpeed(SPEEDS[(current + 1) % SPEEDS.size])
    }

    /**
     * Opens the sensitivity sheet for [event] and [SENSITIVITY_MARGIN] on either side of it, or,
     * with null, for the whole sleep.
     */
    fun openSensitivity(event: SleepEvent?) {
        val (track, detection) = analysis.value ?: return
        val duration = timeline.value?.duration ?: return
        val window = if (event == null) {
            Stretch(Duration.ZERO, duration)
        } else {
            Stretch((event.start - SENSITIVITY_MARGIN).coerceAtLeast(Duration.ZERO), (event.end + SENSITIVITY_MARGIN).coerceAtMost(duration))
        }
        val before = sleepAndEvents.value.second.count { it.start < window.end && it.end > window.start }
        val editor = SensitivityEditor(track, detection, overrides.value, window, anchor = event?.start)
        sheet.value = Sheet(event, editor, before, SensitivitySetting.Unchanged, applying = false)
    }

    /** Previews a start threshold of [db] above the baseline for the sheet's stretch. */
    fun setSensitivity(db: Float) {
        sheet.value = sheet.value?.takeUnless { it.applying }?.copy(setting = SensitivitySetting.Threshold(db)) ?: return
    }

    /** Previews the sheet's stretch without the settings made for it. */
    fun resetSensitivity() {
        sheet.value = sheet.value?.takeUnless { it.applying }?.copy(setting = SensitivitySetting.Inherited) ?: return
    }

    /** Plays the sheet's stretch. */
    fun listenToSensitivity() {
        val window = sheet.value?.editor?.window ?: return
        eventPlayed.value = null
        player.playRange(window.start, window.end)
    }

    /** Stores the sheet's setting, finds the sleep's events again with it and closes the sheet. */
    fun applySensitivity() {
        val open = sheet.value?.takeUnless { it.applying } ?: return
        val threshold = when (val setting = open.setting) {
            SensitivitySetting.Unchanged -> return
            is SensitivitySetting.Threshold -> setting.db
            SensitivitySetting.Inherited -> null
        }
        sheet.value = open.copy(applying = true)
        player.pause()
        viewModelScope.launch {
            val window = open.editor.window
            val events = repository.setSensitivity(sleepId, window.start, window.end, threshold)
            sheet.value = null
            if (events != null) {
                applied.send(SensitivityApplied(open.event == null, events.count { it.start < window.end && it.end > window.start }))
            }
        }
    }

    /** Closes the sensitivity sheet without a change, unless its setting is being applied. */
    fun closeSensitivity() {
        if (sheet.value?.applying != true) sheet.value = null
    }

    override fun onCleared() {
        player.release()
    }

    private companion object {
        /** The speeds the speed button cycles through; they apply only until the screen closes. */
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)

        /** How much of the sleep on either side of an event its sensitivity sheet shows and sets. */
        val SENSITIVITY_MARGIN: Duration = Duration.ofSeconds(30)

        /** The columns the sensitivity chart is computed in, about one per two pixels of a phone. */
        const val CHART_COLUMNS = 400
    }

    /** The open sensitivity sheet: what it was opened for and the setting the user made so far. */
    private data class Sheet(
        val event: SleepEvent?,
        val editor: SensitivityEditor,
        val before: Int,
        val setting: SensitivitySetting,
        val applying: Boolean,
    )
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
private data class ListedEvents(val filter: String?, val events: List<SleepEvent>, val played: List<Stretch>)
