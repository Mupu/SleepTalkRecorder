package me.mupu.sleeptalkrecorder.ui.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

/** What the stats screen counts: the sleep it was opened from, or all sleeps. */
enum class StatsScope { THIS_SLEEP, ALL_SLEEPS }

/** Everything the stats screen shows. */
data class StatsUiState(
    /** Whether the stats screen was opened from a sleep, so that it can switch between the scopes. */
    val hasSleep: Boolean = false,
    val scope: StatsScope = StatsScope.ALL_SLEEPS,
    /** The sleep the screen was opened from; null without one, while loading or once it is gone. */
    val sleep: SleepSummary? = null,
    /** The numbers of [sleep]; null until its events are found, which happens after the stop. */
    val sleepStats: SleepStats? = null,
    /** The numbers of all nights; null while loading. */
    val nightStats: NightStats? = null,
)

/**
 * State of the stats screen: the numbers of the sleep [sleepId] (if it was opened from one) and of
 * all nights, computed from the stored events of every finished sleep whenever they change.
 */
class StatsViewModel(private val sleepId: Long?, repository: SleepRepository, settings: SettingsStore) : ViewModel() {

    private val scope = MutableStateFlow(if (sleepId != null) StatsScope.THIS_SLEEP else StatsScope.ALL_SLEEPS)

    private val computed = combine(repository.storedSleeps(), settings.flow(Settings.sleepWindows)) { stored, windows ->
        val zone = ZoneId.systemDefault()
        val counted = stored.map { CountedSleep.of(it.summary, it.recordings, it.events) }
        val sleep = counted.firstOrNull { it.id == sleepId }
        sleep?.let { SleepStats.of(it, counted, zone) } to NightStats.of(counted, zone, windows)
    }.flowOn(Dispatchers.Default)

    /** The sleep the screen was opened from, also while it is running or its events are not found yet. */
    private val sleep = if (sleepId == null) flowOf(null) else repository.sleep(sleepId)

    val state: StateFlow<StatsUiState> = combine(computed, scope, sleep) { (sleepStats, nightStats), scope, sleep ->
        StatsUiState(
            hasSleep = sleepId != null,
            scope = scope,
            sleep = sleep,
            sleepStats = sleepStats,
            nightStats = nightStats,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState(hasSleep = sleepId != null, scope = scope.value))

    /** Counts the sleep the screen was opened from, or all sleeps. */
    fun setScope(scope: StatsScope) {
        if (sleepId != null) this.scope.value = scope
    }
}
