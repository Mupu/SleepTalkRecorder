package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.EventKindEntity
import me.mupu.sleeptalkrecorder.data.EventKinds
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.detection.Detection
import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.detection.SleepTrack
import me.mupu.sleeptalkrecorder.playback.CutPlan
import me.mupu.sleeptalkrecorder.playback.PlaybackKind
import me.mupu.sleeptalkrecorder.playback.PlaybackState
import me.mupu.sleeptalkrecorder.playback.SleepPlayer
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.transcription.ModelChange
import me.mupu.sleeptalkrecorder.transcription.TranscriptionStatus
import me.mupu.sleeptalkrecorder.transcription.Transcriptions
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State and actions of the sleep screen: the sleep, its events, their playback and speech recognition. */
@OptIn(ExperimentalCoroutinesApi::class)
class SleepViewModel(
    private val sleepId: Long,
    private val repository: SleepRepository,
    settings: SettingsStore,
    private val player: SleepPlayer,
    private val transcriptions: Transcriptions,
    /** The event to play once the sleep is loaded, as when a favourite is opened; null for none. */
    playEventId: Long? = null,
) : ViewModel() {

    private val timeline = MutableStateFlow<SleepTimeline?>(null)
    private val loudness = MutableStateFlow<SleepLoudness?>(null)

    /** The sleep's feature track and its curves, which the sensitivity sheet re-scans; null while being read. */
    private val analysis = MutableStateFlow<Pair<SleepTrack, Detection>?>(null)

    /** Which events the list shows and cut playback plays. */
    private val filter = MutableStateFlow<EventFilter>(EventFilter.All)

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
            // Both take a while for a long night, so they stay off the main thread.
            val (detection, drawn) = withContext(Dispatchers.Default) {
                val detection = EventDetector.detect(track.track)
                detection to SleepLoudness(track, detection.level, detection.baseline)
            }
            analysis.value = track to detection
            loudness.value = drawn
        }
    }

    /**
     * The sleep, its events and the stretches without the microphone. Every stored annotation
     * reads them anew (speech recognition stores a few a second), so they are combined off the
     * main thread and published only when they changed.
     */
    private val sleepAndEvents = combine(repository.sleep(sleepId), repository.events(sleepId), unavailable) { sleep, events, unavailable ->
        Triple(sleep, if (sleep?.eventsFound == true) events else emptyList(), unavailable)
    }.distinctUntilChanged().flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, Triple<SleepSummary?, List<SleepEvent>, List<Stretch>>(null, emptyList(), emptyList()))

    /**
     * The events the list shows, each with the settings' lead-in and tail as far as they reach
     * without a gap or a taken microphone; equal lists are not published again, so the player
     * only hears of real changes.
     */
    private val listed = combine(sleepAndEvents, timeline, leadIn, tail, filter) { (_, all, unavailable), timeline, leadIn, tail, filter ->
        val events = all.filter(filter::shows)
        ListedEvents(
            filter,
            events,
            events.map { event ->
                if (timeline == null) Stretch(event.start, event.end) else eventWithLeadInAndTail(event, timeline, leadIn, tail, unavailable)
            },
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, ListedEvents(EventFilter.All, emptyList(), emptyList()))

    /** What cut playback plays; null while full playback is chosen or the audio is still being read. */
    private val cutPlan = combine(listed, timeline, cut) { listed, timeline, cut ->
        if (!cut || timeline == null) null else CutPlan.of(listed.played)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { cutPlan.collect(player::setCutPlan) }

        // A favourite opened from the library plays on its own once the audio and the events are read.
        if (playEventId != null) {
            viewModelScope.launch {
                timeline.first { it != null }
                val event = sleepAndEvents.first { (_, events, _) -> events.any { it.id == playEventId } }.second.first { it.id == playEventId }
                togglePlayEvent(event)
            }
        }
    }

    /** Where speech recognition stands for the sleep; it starts once the events are found. */
    val transcription: StateFlow<TranscriptionStatus> = combine(
        transcriptions.status(sleepId, repository.transcriptionCount(sleepId)),
        repository.sleep(sleepId),
    ) { status, sleep -> if (sleep?.eventsFound == true) status else TranscriptionStatus.NoEvents }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TranscriptionStatus.NoEvents)

    /**
     * The active model, when it is another than the one that made the sleep's transcripts, to
     * offer a re-run with it; null while speech recognition runs on the sleep.
     */
    val modelChange: StateFlow<ModelChange?> = combine(
        transcriptions.modelChange(repository.transcriptModels(sleepId)),
        transcription,
    ) { change, status -> change.takeIf { status.canRun } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The event kinds the event list can be filtered by. */
    val filterKinds: StateFlow<List<EventKindEntity>> =
        repository.filterKinds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * The position shown: where the timeline is being dragged to, else where the player is. It
     * changes 20 times a second while playing, so it is published on its own, apart from [state].
     */
    val position: StateFlow<Duration> = combine(player.state, scrubPosition) { playback, scrub -> scrub ?: playback.position }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Duration.ZERO)

    /** What the player does, without its position, so that a moving position changes none of it. */
    private val playback = player.state
        .map { PlaybackControls(it.playing, it.speed, it.speedOf, it.failed, playsStretch = it.until != null) }
        .distinctUntilChanged()

    /** The event at the playhead, published only when another one is current. */
    private val current = combine(listed, position) { listed, position -> currentEvent(listed.events, listed.played, position) }
        .distinctUntilChanged()
        .flowOn(Dispatchers.Default)

    val state: StateFlow<SleepUiState> = combine(
        sleepAndEvents,
        combine(timeline, loudness, ::Pair),
        combine(playback, current, ::Pair),
        combine(eventPlayed, cut, cutPlan, ::Triple),
        listed,
    ) { (sleep, events, unavailable), (timeline, loudness), (playback, current), (played, cut, plan), listed ->
        val player = timeline?.let {
            PlayerUiState(
                duration = it.duration,
                playing = playback.playing,
                speed = playback.speed,
                speedOf = playback.speedOf,
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
            timelineEvents = events.filterNot { it.hidden },
            filter = listed.filter,
            listedEvents = listed.events,
            unavailable = unavailable,
            playingEventId = played.takeIf { playback.playing && playback.playsStretch },
            currentEvent = current,
            sensitivityAvailable = loudness != null && sleep?.eventsFound == true && !sleep.isRunning,
        )
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepUiState())

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

    private val hiddenChannel = Channel<SleepEvent>(Channel.BUFFERED)

    /** Each event the user hid, once it is stored, so the hiding can be undone. */
    val eventHidden: Flow<SleepEvent> = hiddenChannel.receiveAsFlow()

    private val applied = Channel<SensitivityApplied>(Channel.BUFFERED)

    /** Each sensitivity applied, once the sleep was searched again with it. */
    val sensitivityApplied: Flow<SensitivityApplied> = applied.receiveAsFlow()

    /** Plays the sleep, in full or cut, from where it stands, or pauses it while it plays. */
    fun togglePlay() {
        if (player.state.value.playing) player.pause() else player.play()
    }

    /** Pauses whatever plays, as when the screen is no longer visible. */
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

    /** Lists, and so plays, the events [filter] shows. */
    fun setFilter(filter: EventFilter) {
        this.filter.value = filter
    }

    /**
     * Hides [event] ([hidden] true) or shows it again. It stays hidden after a restart and when
     * its events are found again; each event hidden is reported once through [eventHidden].
     */
    fun setHidden(event: SleepEvent, hidden: Boolean) {
        viewModelScope.launch {
            repository.setHidden(sleepId, event, hidden)
            if (hidden) hiddenChannel.send(event)
        }
    }

    private val favouriteRemovedChannel = Channel<FavouriteRemoved>(Channel.BUFFERED)

    /** Each event the user removed from the favourites, once it is stored, so that can be undone. */
    val favouriteRemoved: Flow<FavouriteRemoved> = favouriteRemovedChannel.receiveAsFlow()

    /**
     * Keeps [event] as a favourite ([favourite] true) with [name], which shows it again if it was
     * hidden, or removes it from the favourites, reported once through [favouriteRemoved].
     */
    fun setFavourite(event: SleepEvent, favourite: Boolean, name: String? = event.name) {
        viewModelScope.launch {
            val previous = repository.setFavourite(sleepId, event, favourite, name)
            if (!favourite) favouriteRemovedChannel.send(FavouriteRemoved(event, previous))
        }
    }

    /** Makes the event of [change] a favourite again, with the name it had. */
    fun undoFavouriteRemoval(change: FavouriteRemoved) {
        viewModelScope.launch { repository.restoreFavourites(sleepId, change.event, change.previous) }
    }

    private val speechMarkedChannel = Channel<SpeechMarked>(Channel.BUFFERED)

    /** Each event the user marked as speech or as not speech, once it is stored, so that can be undone. */
    val speechMarked: Flow<SpeechMarked> = speechMarkedChannel.receiveAsFlow()

    /**
     * Replaces the words of speech event [event] with the user's [text]; blank text leaves a
     * murmur. The correction wins over speech recognition, also when it runs again.
     */
    fun correctTranscript(event: SleepEvent, text: String) {
        viewModelScope.launch { repository.setUserTranscript(sleepId, event, EventKinds.SPEECH, text.trim().ifBlank { null }) }
    }

    /**
     * Makes [event] a speech event ([speech] true), with the recognised words if speech recognition
     * found any, or turns it into a plain event; reported once through [speechMarked].
     */
    fun markSpeech(event: SleepEvent, speech: Boolean) {
        viewModelScope.launch {
            val previous = if (speech) {
                repository.markSpeech(sleepId, event)
            } else {
                repository.setUserTranscript(sleepId, event, EventKinds.EVENT, null)
            }
            speechMarkedChannel.send(SpeechMarked(event, speech, previous))
        }
    }

    /** Makes the event of [change] what it was before the user marked it. */
    fun undoSpeechMark(change: SpeechMarked) {
        viewModelScope.launch { repository.restoreUserTranscripts(sleepId, change.event, change.previous) }
    }

    /** Recognises speech in every event of the sleep again, now, whatever the settings say. */
    fun runSpeechRecognition() = transcriptions.runNow(sleepId)

    /**
     * Switches what plays now, a single event or the sleep, to its next speed, after the fastest
     * back to the slowest; the speeds apply only until the screen closes.
     */
    fun nextSpeed() = player.nextSpeed()

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

/** The [PlaybackState] of the player without its position; [playsStretch] while a stretch plays on its own. */
private data class PlaybackControls(
    val playing: Boolean,
    val speed: Float,
    val speedOf: PlaybackKind,
    val failed: Boolean,
    val playsStretch: Boolean,
)

/** The events the list shows, and the stretch around each that cut playback plays, in the same order. */
private data class ListedEvents(val filter: EventFilter, val events: List<SleepEvent>, val played: List<Stretch>)
