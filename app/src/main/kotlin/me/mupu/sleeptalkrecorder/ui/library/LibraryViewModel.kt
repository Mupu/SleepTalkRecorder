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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** The sleeps named after one day, shown under that date. */
data class SleepGroup(val date: LocalDate, val sleeps: List<SleepSummary>)

/** The sleep being recorded, with how long it has been recorded so far when known. */
data class RunningSleep(val sleep: SleepSummary, val elapsed: Duration?)

/** Everything the library shows. */
data class LibraryUiState(
    val running: RunningSleep? = null,
    val groups: List<SleepGroup> = emptyList(),
    val loaded: Boolean = false,
)

/** State of the library: every sleep, the running one on top. */
class LibraryViewModel(recorder: Recorder, repository: SleepRepository) : ViewModel() {

    val state: StateFlow<LibraryUiState> = combine(repository.sleeps(), recorder.state) { sleeps, recorderState ->
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

        LibraryUiState(running = running, groups = groups, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())
}
