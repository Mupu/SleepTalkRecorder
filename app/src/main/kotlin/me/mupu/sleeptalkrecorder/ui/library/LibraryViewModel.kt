package me.mupu.sleeptalkrecorder.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.recording.Recorder
import me.mupu.sleeptalkrecorder.recording.RecorderState
import me.mupu.sleeptalkrecorder.time.RecordingClock
import me.mupu.sleeptalkrecorder.time.SleepDate
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The sleeps named after one day, shown under that date. */
data class SleepGroup(val date: LocalDate, val sleeps: List<SleepSummary>)

/** The sleep being recorded, with how long it has been recorded so far when known. */
data class RunningSleep(val sleep: SleepSummary, val elapsed: Duration?)

/** A finished sleep the user asked to delete, with the bytes its files take up. */
data class DeletionRequest(val sleep: SleepSummary, val bytes: Long)

/** Everything the library shows. */
data class LibraryUiState(
    val running: RunningSleep? = null,
    val groups: List<SleepGroup> = emptyList(),
    val loaded: Boolean = false,
    /** The sleep whose deletion waits for the user's confirmation, if any. */
    val deletion: DeletionRequest? = null,
    /** Whether the last deletion left part of its sleep behind. */
    val deletionFailed: Boolean = false,
)

/** State of the library: every sleep, the running one on top; finished sleeps can be deleted. */
class LibraryViewModel(recorder: Recorder, private val repository: SleepRepository) : ViewModel() {

    private val deletion = MutableStateFlow<DeletionRequest?>(null)
    private val deletionFailed = MutableStateFlow(false)

    val state: StateFlow<LibraryUiState> = combine(
        repository.sleeps(),
        recorder.state,
        deletion,
        deletionFailed,
    ) { sleeps, recorderState, deletion, deletionFailed ->
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
            .groupBy { SleepDate.of(it.startedAt, zone).date }
            .map { (date, group) -> SleepGroup(date, group) }

        LibraryUiState(
            running = running,
            groups = groups,
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
