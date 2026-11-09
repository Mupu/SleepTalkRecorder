package me.mupu.sleeptalkrecorder.ui.sleep

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.playback.SleepPlayer
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The player of the sleep screen, once the sleep's audio is known. */
data class PlayerUiState(
    /** How long the whole sleep plays; zero if it has no playable audio. */
    val duration: Duration,
    /** The position shown: the playing position, or where the timeline is being dragged to. */
    val position: Duration,
    /** The instant the sample at [position] was recorded; null without audio. */
    val clock: Instant?,
    val playing: Boolean,
    val speed: Float,
    val failed: Boolean,
) {
    /** Whether there is anything to play. */
    val hasAudio: Boolean get() = duration > Duration.ZERO
}

/** Everything the sleep screen shows. */
data class SleepUiState(
    /** The sleep, or null while loading or once it is gone. */
    val sleep: SleepSummary? = null,
    /** Null while the sleep's audio is being read. */
    val player: PlayerUiState? = null,
)

/** State and actions of the sleep screen: the sleep and its full playback. */
class SleepViewModel(
    sleepId: Long,
    repository: SleepRepository,
    private val player: SleepPlayer,
) : ViewModel() {

    private val timeline = MutableStateFlow<SleepTimeline?>(null)

    /** Where the user is dragging the timeline to; null when not dragging. */
    private val scrubPosition = MutableStateFlow<Duration?>(null)

    init {
        viewModelScope.launch {
            val loaded = repository.timeline(sleepId)
            player.load(loaded)
            timeline.value = loaded
        }
    }

    val state: StateFlow<SleepUiState> = combine(
        repository.sleep(sleepId),
        timeline,
        player.state,
        scrubPosition,
    ) { sleep, timeline, playback, scrub ->
        val player = timeline?.let {
            val position = scrub ?: playback.position
            PlayerUiState(
                duration = it.duration,
                position = position,
                clock = if (it.pieces.isEmpty()) null else it.instantAt(position),
                playing = playback.playing,
                speed = playback.speed,
                failed = playback.failed,
            )
        }
        SleepUiState(sleep, player)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SleepUiState())

    fun togglePlay() {
        if (player.state.value.playing) player.pause() else player.play()
    }

    fun pause() {
        player.pause()
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

    /** Switches to the next speed, after the fastest back to the slowest. */
    fun nextSpeed() {
        val current = SPEEDS.indexOfFirst { it >= player.state.value.speed }
        player.setSpeed(SPEEDS[(current + 1) % SPEEDS.size])
    }

    override fun onCleared() {
        player.release()
    }

    private companion object {
        /** The speeds the speed button cycles through; they apply only until the screen closes. */
        val SPEEDS = listOf(0.75f, 1f, 1.25f, 1.5f, 2f, 2.5f, 3f)
    }
}
