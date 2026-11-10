package me.mupu.sleeptalkrecorder.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.recording.Recorder
import me.mupu.sleeptalkrecorder.recording.RecorderState
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
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
)

/**
 * State of the library: every sleep, the running one on top, and the favourites of all sleeps;
 * finished sleeps can be deleted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(recorder: Recorder, private val repository: SleepRepository) : ViewModel() {

    private val deletion = MutableStateFlow<DeletionRequest?>(null)
    private val deletionFailed = MutableStateFlow(false)

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
        favourites,
    ) { sleeps, recorderState, deletion, deletionFailed, favourites ->
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
}
