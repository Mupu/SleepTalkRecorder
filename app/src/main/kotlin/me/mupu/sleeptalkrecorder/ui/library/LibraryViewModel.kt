package me.mupu.sleeptalkrecorder.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.EventKindEntity
import me.mupu.sleeptalkrecorder.data.EventTag
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepEventOf
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.recording.Recorder
import me.mupu.sleeptalkrecorder.recording.RecorderState
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.share.EventShares
import me.mupu.sleeptalkrecorder.time.RecordingClock
import me.mupu.sleeptalkrecorder.ui.share.EventSelection
import me.mupu.sleeptalkrecorder.ui.share.EventSharing
import me.mupu.sleeptalkrecorder.ui.share.ShareItem
import me.mupu.sleeptalkrecorder.ui.tags.TagEditing
import me.mupu.sleeptalkrecorder.ui.tags.TagFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The sleeps named after one day, shown under that date. */
data class SleepGroup(val date: LocalDate, val sleeps: List<SleepSummary>)

/** The sleep being recorded, with how long it has been recorded so far when known. */
data class RunningSleep(val sleep: SleepSummary, val elapsed: Duration?)

/** A tagged or named event as the library lists it: its event and the clock time it began, if known. */
data class TaggedItem(val sleepId: Long, val event: SleepEvent, val at: Instant?)

/** The tagged events of one sleep, listed under its name. */
data class TaggedGroup(val sleep: SleepSummary, val events: List<TaggedItem>)

/** The events of [groups] that [selection] holds, in the list's order across all sleeps. */
fun selectedTagged(groups: List<TaggedGroup>, selection: EventSelection): List<TaggedItem> =
    groups.flatMap { it.events }.filter { it.event.id in selection.ids }

/** The groups with only the events [filter] shows, without the groups it leaves empty. */
fun List<TaggedGroup>.filtered(filter: TagFilter): List<TaggedGroup> =
    mapNotNull { group -> group.events.filter { filter.shows(it.event) }.takeIf { it.isNotEmpty() }?.let { group.copy(events = it) } }

/** A finished sleep the user asked to delete, with the bytes its files take up. */
data class DeletionRequest(val sleep: SleepSummary, val bytes: Long)

/** Everything the library shows. */
data class LibraryUiState(
    val running: RunningSleep? = null,
    val groups: List<SleepGroup> = emptyList(),
    /** The tagged and named events of all sleeps that the [filter] shows, the newest sleep first. */
    val tagged: List<TaggedGroup> = emptyList(),
    /** Every tagged and named event, whatever the filter, which the filter window counts. */
    val allTagged: List<SleepEvent> = emptyList(),
    /** Which tagged events the second tab lists. */
    val filter: TagFilter = TagFilter(),
    val loaded: Boolean = false,
    /** The sleep whose deletion waits for the user's confirmation, if any. */
    val deletion: DeletionRequest? = null,
    /** Whether the last deletion left part of its sleep behind. */
    val deletionFailed: Boolean = false,
    /** The events selected together, of any sleeps, in the list's order; selection mode while there are any. */
    val selected: List<TaggedItem> = emptyList(),
)

/**
 * State of the library: every sleep, the running one on top, and the events of all sleeps that
 * have a tag of the user or a name, filtered by tags; finished sleeps can be deleted. Several
 * tagged events, also of different sleeps, can be selected to share or tag them together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    recorder: Recorder,
    private val repository: SleepRepository,
    private val settings: SettingsStore,
    shares: EventShares,
) : ViewModel() {

    private val deletion = MutableStateFlow<DeletionRequest?>(null)
    private val deletionFailed = MutableStateFlow(false)

    /** The tagged events selected together, by event id; selection mode while any is still listed. */
    private val selection = MutableStateFlow(EventSelection())

    /** Which tagged events the second tab lists. */
    private val filter = MutableStateFlow(TagFilter())

    /** The timelines of the sleeps with tagged events, read once each, for the events' clock times. */
    private val timelines = mutableMapOf<Long, SleepTimeline>()

    private val tagged = combine(repository.sleeps(), repository.taggedEvents()) { sleeps, tagged -> sleeps to tagged }
        .mapLatest { (sleeps, tagged) ->
            val byId = sleeps.associateBy { it.id }
            tagged.groupBy { it.sleepId }.mapNotNull { (sleepId, of) ->
                val sleep = byId[sleepId] ?: return@mapNotNull null
                val timeline = timelines.getOrPut(sleepId) { repository.timeline(sleepId) }
                TaggedGroup(
                    sleep,
                    of.map { TaggedItem(sleepId, it.event, timeline.takeIf { t -> t.pieces.isNotEmpty() }?.instantAt(it.event.start)) },
                )
            }
        }

    val state: StateFlow<LibraryUiState> = combine(
        repository.sleeps(),
        recorder.state,
        deletion,
        deletionFailed,
        combine(tagged, filter, selection, ::Triple),
    ) { sleeps, recorderState, deletion, deletionFailed, (all, filter, selection) ->
        val zone = ZoneId.systemDefault()
        val progress = (recorderState as? RecorderState.Recording)?.progress

        val running = sleeps.firstOrNull { it.isRunning }?.let { sleep ->
            val elapsed = progress
                ?.takeIf { it.sleepId == sleep.id }
                ?.let { RecordingClock(it.startedAt, it.sampleRate).durationOf(it.frames) }
            RunningSleep(sleep, elapsed)
        }
        val groups = sleeps
            .filterNot { it.isRunning }
            .groupBy { it.date.date }
            .map { (date, group) -> SleepGroup(date, group) }
        val shown = all.filtered(filter)

        LibraryUiState(
            running = running,
            groups = groups,
            tagged = shown,
            allTagged = all.flatMap { group -> group.events.map { it.event } },
            filter = filter,
            loaded = true,
            deletion = deletion,
            deletionFailed = deletionFailed,
            selected = selectedTagged(shown, selection),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    /** Asks to delete [sleep]; the confirmation names the size of its files. */
    fun askToDelete(sleep: SleepSummary) {
        if (sleep.isRunning) return
        viewModelScope.launch { deletion.value = DeletionRequest(sleep, repository.sizeOf(sleep.id)) }
    }

    fun cancelDeletion() {
        deletion.value = null
    }

    /** Deletes the sleep the user confirmed; it leaves the list once it is gone. */
    fun confirmDeletion() {
        val request = deletion.value ?: return
        deletion.value = null
        viewModelScope.launch { deletionFailed.value = !repository.deleteSleep(request.sleep.id) }
    }

    /** The failure message was shown. */
    fun deletionFailureShown() {
        deletionFailed.value = false
    }

    /** The event kinds the filter offers as system tags. */
    val filterKinds: StateFlow<List<EventKindEntity>> =
        repository.filterKinds().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Chooses [tag] in the filter, or takes it out. */
    fun toggleFilterTag(tag: EventTag) {
        filter.update { it.toggle(tag) }
    }

    /** Lists every tagged event that is not hidden again. */
    fun clearFilter() {
        filter.value = TagFilter()
    }

    /** The user's tags and the tag window of the selected events; a deleted tag leaves the filter. */
    val tagging = TagEditing(viewModelScope, repository, settings, onDeleted = { id -> filter.update { it.without(id) } })

    /** Opens the tag window for the selected events; selection mode ends once its choice is applied. */
    fun tagSelected() {
        tagging.open(state.value.selected.map { SleepEventOf(it.sleepId, it.event) })
    }

    /** Adds [item] to the selection, which starts selection mode, or takes it out again. */
    fun toggleSelection(item: TaggedItem) {
        selection.value = selection.value.toggle(item.event)
    }

    /** Ends selection mode. */
    fun clearSelection() {
        selection.value = EventSelection()
    }

    /**
     * The step that shares several selected events, without a player to listen with; sharing
     * them ends selection mode. A single event is shared as [sharedEvent] instead.
     */
    val sharing = EventSharing(viewModelScope, settings, shares, onShared = ::clearSelection)

    private val sharedEventFlow = MutableStateFlow<TaggedItem?>(null)

    /**
     * The single tagged event whose share step is open over the library: the step of the sleep
     * screen, read with its sleep's loudness and player, so its stretch can be moved and listened
     * to; null while none is.
     */
    val sharedEvent: StateFlow<TaggedItem?> = sharedEventFlow

    /** Opens the share step of [item] alone. */
    fun shareEvent(item: TaggedItem) {
        sharedEventFlow.value = item
    }

    /** The share step of [sharedEvent] closed. */
    fun eventShareClosed() {
        sharedEventFlow.value = null
    }

    /**
     * Opens the share step for the selected events: for a single one that of [shareEvent],
     * for several [sharing], each with the audio of its own sleep as it is now, once that is read.
     */
    fun shareSelected() {
        val current = state.value
        val single = current.selected.singleOrNull()
        if (single != null) {
            shareEvent(single)
            return
        }
        if (current.selected.isEmpty()) return
        val sleeps = current.tagged.associate { it.sleep.id to it.sleep }
        viewModelScope.launch {
            // Read anew rather than taken from [timelines]: a sleep may have grown since.
            val audio = current.selected.map { it.sleepId }.distinct().associateWith { sleepId ->
                repository.timeline(sleepId) to repository.microphoneUnavailable(sleepId).first()
            }
            val items = current.selected.map { item ->
                val (timeline, unavailable) = audio.getValue(item.sleepId)
                ShareItem(item.event, sleeps[item.sleepId], timeline, unavailable)
            }
            sharing.open(items)
        }
    }
}
