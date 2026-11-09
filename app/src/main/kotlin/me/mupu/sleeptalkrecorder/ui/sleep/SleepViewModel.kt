package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/** State of the sleep screen: the sleep it shows, or null while loading or once it is gone. */
class SleepViewModel(sleepId: Long, repository: SleepRepository) : ViewModel() {

    val sleep: StateFlow<SleepSummary?> = repository.sleep(sleepId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}
