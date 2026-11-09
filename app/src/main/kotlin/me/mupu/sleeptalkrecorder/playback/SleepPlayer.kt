package me.mupu.sleeptalkrecorder.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.time.Duration
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** What the player is doing, with its position on the [SleepTimeline]. */
data class PlaybackState(
    /** Whether audio is playing or about to play; false when paused or at the end. */
    val playing: Boolean = false,
    val position: Duration = Duration.ZERO,
    /** Playing speed; 1 is the recorded speed, and the pitch stays the same at every speed. */
    val speed: Float = 1f,
    /** Whether the last attempt to play failed, for example on a damaged file. */
    val failed: Boolean = false,
)

/**
 * Plays a sleep in full: every chunk file of its [SleepTimeline] as one item of a Media3 playlist.
 * The files are PCM WAV in one format, which has no encoder delay or padding, so ExoPlayer moves
 * from one file to the next without a gap and keeps the same audio track.
 *
 * The player lives on the main thread; call [release] when it is no longer needed.
 */
class SleepPlayer(context: Context) {

    private val player = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val scope = MainScope()
    private var ticker: Job? = null
    private var timeline: SleepTimeline? = null

    private val mutableState = MutableStateFlow(PlaybackState())

    /** The current state; the position is updated several times a second while playing. */
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    publish()
                    if (player.isPlaying) startTicker() else stopTicker()
                }

                override fun onPlayerError(error: PlaybackException) {
                    mutableState.value = mutableState.value.copy(failed = true)
                }
            },
        )
    }

    /** Prepares [timeline] for playing from its start. */
    fun load(timeline: SleepTimeline) {
        this.timeline = timeline
        player.setMediaItems(timeline.pieces.map { MediaItem.fromUri(Uri.fromFile(it)) })
        player.prepare()
    }

    /** Plays from the current position; at the end of the sleep, from its start. */
    fun play() {
        if (timeline?.pieces.isNullOrEmpty()) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0, 0)
        // A failed player stays idle until it is prepared again.
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        mutableState.value = mutableState.value.copy(failed = false)
        player.play()
    }

    fun pause() {
        player.pause()
    }

    /** Moves to [position] of the sleep, held within its start and end. */
    fun seekTo(position: Duration) {
        val timeline = timeline?.takeIf { it.pieces.isNotEmpty() } ?: return
        val location = timeline.locate(position)
        player.seekTo(location.piece, location.offset.toMillis())
        publish()
    }

    /** Plays at [speed] times the recorded speed with the recorded pitch. */
    fun setSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
    }

    /** Stops playing and frees the player. */
    fun release() {
        scope.cancel()
        player.release()
    }

    private fun publish() {
        mutableState.value = mutableState.value.copy(
            playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED &&
                player.playbackState != Player.STATE_IDLE,
            position = currentPosition(),
            speed = player.playbackParameters.speed,
        )
    }

    private fun currentPosition(): Duration {
        val timeline = timeline?.takeIf { it.pieces.isNotEmpty() } ?: return Duration.ZERO
        val piece = player.currentMediaItemIndex.coerceIn(0, timeline.pieces.lastIndex)
        return timeline.positionOf(piece, Duration.ofMillis(player.currentPosition))
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                publish()
                delay(TICK_MILLIS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private companion object {
        /** How often the position is published while playing; enough for a smooth clock. */
        const val TICK_MILLIS = 100L
    }
}
