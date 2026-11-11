package me.mupu.sleeptalkrecorder.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.data.SleepEvent
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
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The sleeps named after one day, shown under that date. */
data class SleepGroup(val date: LocalDate, val sleeps: List<SleepSummary>)

/** The sleep being recorded, with how long it has been recorded so far when known. */
data class RunningSleep(val sleep: SleepSummary, val elapsed: Duration?)

/** A favourite as the library lists it: its event and the clock time it began, if known. */
data class FavouriteItem(val sleepId: Long, val event: SleepEvent, val at: Instant?)

/** The favourites of one sleep, listed under its name. */
data class FavouriteGroup(val sleep: SleepSummary, val favourites: List<FavouriteItem>)

/** The favourites of [groups] that [selection] holds, in the list's order across all sleeps. */
fun selectedFavourites(groups: List<FavouriteGroup>, selection: EventSelection): List<FavouriteItem> =
    groups.flatMap { it.favourites }.filter { it.event.id in selection.ids }

/** A favourite the user removed from the favourites, with its favourite marks before, which undo restores. */
class RemovedFavourite(val favourite: FavouriteItem, val previous: List<AnnotationEntity>)

/** A finished sleep the user asked to delete, with the bytes its files take up. */
data class DeletionRequest(val sleep: SleepSummary, val bytes: Long)

/** Everything the library shows. */
data class LibraryUiState(
    val running: RunningSleep? = null,
    val groups: List<SleepGroup> = emptyList(),
    /** The favourites of all sleeps, the newest sleep first. */
    val favourites: List<FavouriteGroup> = emptyList(),
    val loaded: Boolean = false,
    /** The sleep whose deletion waits for the user's confirmation, if any. */
    val deletion: DeletionRequest? = null,
    /** Whether the last deletion left part of its sleep behind. */
    val deletionFailed: Boolean = false,
    /** The favourites selected together, of any sleeps, in the list's order; selection mode while there are any. */
    val selected: List<FavouriteItem> = emptyList(),
)

/**
 * State of the library: every sleep, the running one on top, and the favourites of all sleeps;
 * finished sleeps can be deleted. Several favourites, also of different sleeps, can be selected to
 * share them together or remove them from the favourites.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(
    recorder: Recorder,
    private val repository: SleepRepository,
    settings: SettingsStore,
    shares: EventShares,
) : ViewModel() {

    private val deletion = MutableStateFlow<DeletionRequest?>(null)
    private val deletionFailed = MutableStateFlow(false)

    /** The favourites selected together, by event id; selection mode while any is still a favourite. */
    private val selection = MutableStateFlow(EventSelection())

    /** The timelines of the sleeps with favourites, read once each, for the favourites' clock times. */
    private val timelines = mutableMapOf<Long, SleepTimeline>()

    private val favourites = combine(repository.sleeps(), repository.favourites()) { sleeps, favourites -> sleeps to favourites }
        .mapLatest { (sleeps, favourites) ->
            val byId = sleeps.associateBy { it.id }
            favourites.groupBy { it.sleepId }.mapNotNull { (sleepId, of) ->
                val sleep = byId[sleepId] ?: return@mapNotNull null
                val timeline = timelines.getOrPut(sleepId) { repository.timeline(sleepId) }
                FavouriteGroup(
                    sleep,
                    of.map { FavouriteItem(sleepId, it.event, timeline.takeIf { t -> t.pieces.isNotEmpty() }?.instantAt(it.event.start)) },
                )
            }
        }

    val state: StateFlow<LibraryUiState> = combine(
        repository.sleeps(),
        recorder.state,
        deletion,
        deletionFailed,
        combine(favourites, selection, ::Pair),
    ) { sleeps, recorderState, deletion, deletionFailed, (favourites, selection) ->
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

        LibraryUiState(
            running = running,
            groups = groups,
            favourites = favourites,
            loaded = true,
            deletion = deletion,
            deletionFailed = deletionFailed,
            selected = selectedFavourites(favourites, selection),
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

    /** Adds [favourite] to the selection, which starts selection mode, or takes it out again. */
    fun toggleSelection(favourite: FavouriteItem) {
        selection.value = selection.value.toggle(favourite.event)
    }

    /** Ends selection mode. */
    fun clearSelection() {
        selection.value = EventSelection()
    }

    private val removedChannel = Channel<List<RemovedFavourite>>(Channel.BUFFERED)

    /** The favourites the user removed together, once that is stored, so it can be undone. */
    val favouritesRemoved: Flow<List<RemovedFavourite>> = removedChannel.receiveAsFlow()

    /** Removes the selected favourites from the favourites, which a snackbar undoes, and ends selection mode. */
    fun removeSelected() {
        val selected = state.value.selected
        clearSelection()
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val removed = selected.map { RemovedFavourite(it, repository.setFavourite(it.sleepId, it.event, false, it.event.name)) }
            removedChannel.send(removed)
        }
    }

    /** Makes the favourites of [removed] favourites again, each with the name it had. */
    fun undoRemoval(removed: List<RemovedFavourite>) {
        viewModelScope.launch {
            for (it in removed) repository.restoreFavourites(it.favourite.sleepId, it.favourite.event, it.previous)
        }
    }

    /**
     * The step that shares several selected favourites, without a player to listen with; sharing
     * them ends selection mode. A single favourite is shared as [sharedFavourite] instead.
     */
    val sharing = EventSharing(viewModelScope, settings, shares, onShared = ::clearSelection)

    private val sharedFavouriteFlow = MutableStateFlow<FavouriteItem?>(null)

    /**
     * The single favourite whose share step is open over the library: the step of the sleep
     * screen, read with its sleep's loudness and player, so its stretch can be moved and listened
     * to; null while none is.
     */
    val sharedFavourite: StateFlow<FavouriteItem?> = sharedFavouriteFlow

    /** Opens the share step of [favourite] alone. */
    fun shareFavourite(favourite: FavouriteItem) {
        sharedFavouriteFlow.value = favourite
    }

    /** The share step of [sharedFavourite] closed. */
    fun favouriteShareClosed() {
        sharedFavouriteFlow.value = null
    }

    /**
     * Opens the share step for the selected favourites: for a single one that of [shareFavourite],
     * for several [sharing], each with the audio of its own sleep as it is now, once that is read.
     */
    fun shareSelected() {
        val current = state.value
        val single = current.selected.singleOrNull()
        if (single != null) {
            shareFavourite(single)
            return
        }
        if (current.selected.isEmpty()) return
        val sleeps = current.favourites.associate { it.sleep.id to it.sleep }
        viewModelScope.launch {
            // Read anew rather than taken from [timelines]: a sleep may have grown since.
            val audio = current.selected.map { it.sleepId }.distinct().associateWith { sleepId ->
                repository.timeline(sleepId) to repository.microphoneUnavailable(sleepId).first()
            }
            val items = current.selected.map { favourite ->
                val (timeline, unavailable) = audio.getValue(favourite.sleepId)
                ShareItem(favourite.event, sleeps[favourite.sleepId], timeline, unavailable)
            }
            sharing.open(items)
        }
    }
}
