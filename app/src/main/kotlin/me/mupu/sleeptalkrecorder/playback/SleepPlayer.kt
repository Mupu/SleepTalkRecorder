package me.mupu.sleeptalkrecorder.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.wav.WavExtractor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToLong
import kotlin.math.sign
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
    /** Whose speed [speed] and [boost] are: a stretch played on its own has its own of both. */
    val kind: PlaybackKind = PlaybackKind.SLEEP,
    /** The volume boost, a gain from [PlaybackBoosts.STEPS]; 1 is off. */
    val boost: Int = 1,
    /** Whether the last attempt to play failed, for example on a damaged file. */
    val failed: Boolean = false,
    /** Where playback pauses on its own while a stretch is played with [SleepPlayer.playRange]. */
    val until: Duration? = null,
)

/**
 * Plays a sleep: its [SleepTimeline] as one [SleepAudio] stream, in full or, with a [CutPlan], cut.
 *
 * In cut playback the player jumps from the end of each stretch to just before the start of the
 * next, and an audio processor in the output path fades each stretch in and out and silences
 * everything between them. The processor works on the samples themselves, by their position in
 * the stream, so the fades sit exactly at the stretch edges whenever the jump itself happens; the
 * jump only decides how long the silence between two stretches lasts. The processor also fades in
 * after every seek, in full playback too, so playback never starts with a click.
 *
 * A stretch played on its own ([playRange]) plays at the event speed and with the event boost,
 * everything else at the sleep speed and with the sleep boost ([PlaybackSpeeds], [PlaybackBoosts]).
 *
 * The player lives on the main thread; call [release] when it is no longer needed.
 */
@OptIn(UnstableApi::class)
class SleepPlayer(
    context: Context,
    /** Told whether audio plays, whenever that changes; the updater waits while it does. */
    private val onPlayingChange: (Boolean) -> Unit = {},
) {

    private val fader = CutFader()

    private val player = ExoPlayer.Builder(context, FaderRenderersFactory(context, fader))
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(),
            /* handleAudioFocus = */ true,
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val scope = MainScope()
    private var ticker: Job? = null
    private var timeline: SleepTimeline? = null
    private var sampleRate = 0
    private var plan: CutPlan? = null
    private var speeds = PlaybackSpeeds()
    private var boosts = PlaybackBoosts()

    private val mutableState = MutableStateFlow(PlaybackState())

    /** The current state; the position is updated several times a second while playing. */
    val state: StateFlow<PlaybackState> = mutableState.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    publish()
                    if (player.isPlaying) startTicker() else stopTicker()
                    if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) onPlayingChange(player.isPlaying)
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
        sampleRate = SleepAudio.sampleRateOf(timeline)
        fader.envelope = plan?.envelope(sampleRate)

        val source = ProgressiveMediaSource.Factory({ SleepAudioDataSource(timeline) }, WAV_ONLY)
            .createMediaSource(MediaItem.fromUri(SLEEP_URI))
        player.setMediaSource(source)
        player.prepare()
    }

    /**
     * Plays only the stretches of [plan] from now on (cut playback), or the whole sleep when it is
     * null (full playback). A playing player jumps straight to the next stretch when it is
     * between two. A plan with the same stretches as the current one changes nothing.
     */
    fun setCutPlan(plan: CutPlan?) {
        if (plan?.stretches == this.plan?.stretches) return
        this.plan = plan
        fader.envelope = plan?.envelope(sampleRate)
        // The audio already processed carries the old gains; seeking in place replaces it, and the
        // fader fades in, so the change never steps the gain in the middle of a wave.
        if (player.isPlaying) player.seekTo(player.currentPosition)
        publish()
    }

    /**
     * Plays from the current position; at the end of the sleep, from its start. In cut playback
     * it starts at the next stretch, and after the last one again at the first.
     */
    fun play() {
        setUntil(null)
        val plan = plan
        if (plan != null) {
            val from = plan.continueFrom(currentPosition()) ?: plan.stretches.firstOrNull()?.start ?: return
            jumpTo(from)
        }
        start()
    }

    /** Plays the stretch from [from] until [until] and pauses there, as when an event is played on its own. */
    fun playRange(from: Duration, until: Duration) {
        seekTo(from)
        setUntil(until)
        start()
    }

    private fun start() {
        if (timeline?.pieces.isNullOrEmpty()) return
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        // A failed player stays idle until it is prepared again.
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        mutableState.value = mutableState.value.copy(failed = false)
        player.play()
    }

    /** Pauses at the current position; a stretch being played on its own ends there. */
    fun pause() {
        setUntil(null)
        player.pause()
    }

    /** Moves to [position] of the sleep, held within its start and end; a stretch being played ends there. */
    fun seekTo(position: Duration) {
        setUntil(null)
        val timeline = timeline?.takeIf { it.pieces.isNotEmpty() } ?: return
        player.seekTo(position.coerceIn(Duration.ZERO, timeline.duration).toMillis())
        publish()
    }

    /**
     * Switches the speed of what plays now, a stretch on its own or the sleep, to the next one;
     * the other keeps its speed.
     */
    fun nextSpeed() {
        speeds = speeds.next(playingKind())
        applyKind()
        publish()
    }

    /**
     * Switches the volume boost of what plays now, a stretch on its own or the sleep, to the next
     * one, after the strongest back to off; the other keeps its boost. Playback goes on.
     */
    fun nextBoost() {
        boosts = boosts.next(playingKind())
        applyKind()
        publish()
    }

    /** Whose speed and boost apply now: those of a stretch played on its own while one plays, else the sleep's. */
    private fun playingKind(): PlaybackKind = if (mutableState.value.until != null) PlaybackKind.EVENT else PlaybackKind.SLEEP

    /** Sets where playback pauses on its own, or with null that it plays on, and the speed and boost that go with it. */
    private fun setUntil(until: Duration?) {
        mutableState.value = mutableState.value.copy(until = until)
        applyKind()
    }

    /** Plays at the speed, with the recorded pitch, and with the boost of what plays now. */
    private fun applyKind() {
        val kind = playingKind()
        val speed = speeds.of(kind)
        if (player.playbackParameters.speed != speed) player.setPlaybackSpeed(speed)
        fader.boost = boosts.of(kind).toFloat()
    }

    /** Stops playing and frees the player. */
    fun release() {
        scope.cancel()
        player.release()
        onPlayingChange(false)
    }

    private fun publish() {
        val position = currentPosition()
        val until = mutableState.value.until
        if (until != null && position >= until) {
            pause()
            return
        }
        val playing = player.playWhenReady && player.playbackState != Player.STATE_ENDED &&
            player.playbackState != Player.STATE_IDLE

        // Cut playback jumps over what lies between two stretches and stops after the last; a
        // stretch played on its own lies inside one and ends by itself.
        val plan = plan
        if (plan != null && playing && until == null) {
            val next = plan.continueFrom(position)
            if (next == null) {
                pause()
                return
            }
            if (jumpTo(next)) return
        }

        if (until != null && !playing) setUntil(null)
        val kind = playingKind()
        mutableState.value = mutableState.value.copy(
            playing = playing,
            position = position,
            speed = speeds.of(kind),
            kind = kind,
            boost = boosts.of(kind),
        )
    }

    /**
     * Moves cut playback on to the stretch that starts at [start], landing [JUMP_AHEAD] before it;
     * returns whether it moved, which it does not when the stretch is about to begin or playing.
     */
    private fun jumpTo(start: Duration): Boolean {
        val position = currentPosition()
        if (start in position..position + JUMP_AHEAD) return false
        player.seekTo((start - JUMP_AHEAD).coerceAtLeast(Duration.ZERO).toMillis())
        return true
    }

    private fun currentPosition(): Duration =
        if (timeline?.pieces.isNullOrEmpty()) Duration.ZERO else Duration.ofMillis(player.currentPosition)

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
        /**
         * How often the position is published while playing; enough for a smooth clock. A stretch
         * played on its own ends, and cut playback jumps, at most this much late.
         */
        const val TICK_MILLIS = 50L

        /**
         * How far before the next stretch a jump of cut playback lands. After a seek ExoPlayer
         * starts a WAV stream at the next of its 100 ms blocks of samples, so a jump straight to
         * the stretch could cut off its start; landing earlier plays a moment of the silence
         * between the stretches instead.
         */
        val JUMP_AHEAD: Duration = Duration.ofMillis(200)

        /** The sleep's stream has no file of its own; its data source ignores the address. */
        val SLEEP_URI: Uri = "sleep:audio".toUri()

        val WAV_ONLY = ExtractorsFactory { arrayOf(WavExtractor()) }
    }
}

/** Serves the [SleepAudio] of a timeline to ExoPlayer; each instance reads on one loader thread. */
@OptIn(UnstableApi::class)
private class SleepAudioDataSource(timeline: SleepTimeline) : BaseDataSource(/* isNetwork = */ false) {

    private val audio = SleepAudio(timeline)
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        position = dataSpec.position
        val available = (audio.length - position).coerceAtLeast(0)
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) available else minOf(dataSpec.length, available)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT

        val read = audio.read(position, buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (read < 0) return C.RESULT_END_OF_INPUT
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        audio.close()
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }
}

/** The renderers ExoPlayer uses by default, with [fader] in the audio output path. */
@OptIn(UnstableApi::class)
private class FaderRenderersFactory(context: Context, private val fader: CutFader) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(fader))
            .build()
}

/**
 * Multiplies each 16-bit sample with the gain its position has in cut playback ([envelope]; full
 * playback has none), and fades the sound in wherever the stream continues somewhere else than
 * where it stopped, after a seek or a jump, so that playback never starts in the middle of a wave.
 * On top it amplifies by the volume [boost], limited so that loud parts do not clip
 * ([BoostLimiter]).
 *
 * ExoPlayer runs it on its playback thread before the speed is changed, so it sees the samples of
 * the stream at their own rate. After every flush (a seek, a change of speed) ExoPlayer tells it
 * where the stream continues, and it counts the samples from there.
 */
@OptIn(UnstableApi::class)
private class CutFader : BaseAudioProcessor() {

    /** The gains of cut playback; set from the main thread, read on the playback thread. */
    @Volatile
    var envelope: CutEnvelope? = null

    /**
     * The volume boost, 1 for none; set from the main thread, read on the playback thread. A
     * change glides in by [BOOST_GLIDE] per fade instead of stepping the gain in the middle of a
     * wave, and reaches the ear only after the audio ExoPlayer has already processed.
     */
    @Volatile
    var boost = 1f

    /** The boost the last sample was amplified with, on its way to [boost]. */
    private var appliedBoost = 1f

    /** Limits boosted samples; made for the sample rate of the stream. */
    private var limiter = BoostLimiter(1)

    /** The samples of one frame, one per channel, while they pass the [limiter]. */
    private var samples = ShortArray(0)

    /** The position in the stream of the next sample to arrive. */
    private var frame = 0L

    /** Where the stream last continued at another position, and the samples its fade-in takes. */
    private var onset = 0L
    private var fadeFrames = 1L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        fadeFrames = CutPlan.fadeFrames(inputAudioFormat.sampleRate)
        limiter = BoostLimiter(inputAudioFormat.sampleRate)
        samples = ShortArray(inputAudioFormat.channelCount)
        return inputAudioFormat
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val continuesAt = (streamMetadata.positionOffsetUs * inputAudioFormat.sampleRate / MICROS_PER_SECOND.toDouble()).roundToLong()
        // A flush for a change of speed continues where the stream was and needs no fade, and the
        // limiter keeps following it. Elsewhere, as after a jump to an event played on its own,
        // the boost of what plays now applies from the first sample and the limiter starts afresh.
        if (continuesAt != frame) {
            onset = continuesAt
            appliedBoost = boost
            limiter.reset()
        }
        frame = continuesAt
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val frames = inputBuffer.remaining() / (BYTES_PER_SAMPLE * channels)
        val output = replaceOutputBuffer(inputBuffer.remaining()).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val envelope = envelope
        val boost = boost
        val glide = BOOST_GLIDE / fadeFrames
        for (i in 0 until frames) {
            val at = frame + i
            val fadeIn = if (at - onset >= fadeFrames) 1f else (at - onset).toFloat() / fadeFrames
            appliedBoost = if (abs(boost - appliedBoost) <= glide) boost else appliedBoost + glide * sign(boost - appliedBoost)
            val gain = fadeIn * (envelope?.gainAt(at) ?: 1f) * appliedBoost
            for (channel in 0 until channels) samples[channel] = input.getShort()
            limiter.process(samples, gain, appliedBoost)
            for (sample in samples) output.putShort(sample)
        }
        frame += frames
        output.flip()
    }

    private companion object {
        const val BYTES_PER_SAMPLE = 2
        const val MICROS_PER_SECOND = 1_000_000L

        /** How far the boost moves during one fade ([CutPlan.fadeFrames]): from off to 8× in seven fades. */
        const val BOOST_GLIDE = 1f
    }
}
