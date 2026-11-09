package me.mupu.sleeptalkrecorder.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.recording.Recorder
import me.mupu.sleeptalkrecorder.recording.RecorderState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.stateIn

/** Everything the home screen shows. */
data class HomeUiState(
    val recorder: RecorderState = RecorderState.Idle,
    /** The levels of the most recent buffers in dBFS, oldest first; empty when not recording. */
    val levels: List<Double> = emptyList(),
    /** The most recent finished sleep, if there is one. */
    val lastSleep: SleepSummary? = null,
)

/** State and actions of the home screen. */
class HomeViewModel(
    private val recorder: Recorder,
    repository: SleepRepository,
) : ViewModel() {

    private val levels = recorder.state.runningFold(emptyList<Double>()) { history, state ->
        val progress = (state as? RecorderState.Recording)?.progress ?: return@runningFold emptyList()
        (history + progress.levelDbfs).takeLast(METER_BARS)
    }

    private val lastSleep = repository.sleeps().map { sleeps -> sleeps.firstOrNull { !it.isRunning } }

    val state: StateFlow<HomeUiState> = combine(recorder.state, levels, lastSleep, ::HomeUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState(recorder.state.value))

    fun startRecording() = recorder.start()

    fun stopRecording() = recorder.stop()

    companion object {
        /** How many buffers the level meter shows. */
        const val METER_BARS = 21
    }
}
