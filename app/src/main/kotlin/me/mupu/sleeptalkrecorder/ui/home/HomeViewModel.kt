package me.mupu.sleeptalkrecorder.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.NightFindings
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.readiness.PhoneReadiness
import me.mupu.sleeptalkrecorder.readiness.Requirement
import me.mupu.sleeptalkrecorder.recording.Recorder
import me.mupu.sleeptalkrecorder.recording.RecorderState
import me.mupu.sleeptalkrecorder.update.UpdateState
import me.mupu.sleeptalkrecorder.update.Updater
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
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
    /** What the phone does not allow yet that a whole night needs; home warns while it is not empty. */
    val missing: Set<Requirement> = emptySet(),
    /** Where the update stands; home shows an update once one is found. */
    val update: UpdateState = UpdateState.Off,
    /** What the scan during the night found in the sleep being recorded; null while it is off or nothing is recorded. */
    val night: NightScanState? = null,
)

/** The scan during the night of the sleep being recorded: what it [found] so far, null before its first part. */
data class NightScanState(val found: NightFindings?)

/** State and actions of the home screen. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val recorder: Recorder,
    repository: SleepRepository,
    readiness: PhoneReadiness,
    private val updater: Updater,
    nightScan: Flow<Boolean>,
) : ViewModel() {

    private val levels = recorder.state.runningFold(emptyList<Double>()) { history, state ->
        val progress = (state as? RecorderState.Recording)?.progress ?: return@runningFold emptyList()
        (history + progress.levelDbfs).takeLast(METER_BARS)
    }

    private val lastSleep = repository.sleeps().map { sleeps -> sleeps.firstOrNull { !it.isRunning } }

    private val recordedSleep = recorder.state.map { (it as? RecorderState.Recording)?.progress?.sleepId }.distinctUntilChanged()

    private val night = combine(recordedSleep, nightScan, ::Pair).flatMapLatest { (sleepId, on) ->
        if (sleepId == null || !on) flowOf(null) else repository.nightFindings(sleepId).map(::NightScanState)
    }

    val state: StateFlow<HomeUiState> = combine(combine(recorder.state, levels, ::Pair), lastSleep, readiness.missing, updater.state, night) {
            (recorder, levels), lastSleep, missing, update, night ->
        HomeUiState(recorder, levels, lastSleep, missing, update, night)
    }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            HomeUiState(recorder.state.value, missing = readiness.missing.value, update = updater.state.value),
        )

    fun startRecording() = recorder.start()

    fun stopRecording() = recorder.stop()

    fun downloadUpdate() = updater.download()

    fun installUpdate() = updater.install()

    companion object {
        /** How many buffers the level meter shows. */
        const val METER_BARS = 21
    }
}
