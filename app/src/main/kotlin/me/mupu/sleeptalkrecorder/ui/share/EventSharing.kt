package me.mupu.sleeptalkrecorder.ui.share

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.share.CaptionItem
import me.mupu.sleeptalkrecorder.share.CardColors
import me.mupu.sleeptalkrecorder.share.EventAudio
import me.mupu.sleeptalkrecorder.share.EventLabels
import me.mupu.sleeptalkrecorder.share.EventShares
import me.mupu.sleeptalkrecorder.share.ShareFormat
import me.mupu.sleeptalkrecorder.share.ShareStretch
import me.mupu.sleeptalkrecorder.share.ShareStretches
import me.mupu.sleeptalkrecorder.share.SharedEvent
import me.mupu.sleeptalkrecorder.share.caption
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.sleep.SleepLoudness
import java.time.Duration

/**
 * An [event] in a share step with its [sleep], whose audio its stretch is cut from: the sleep's
 * [timeline] and the stretches in which its microphone was [unavailable].
 */
data class ShareItem(val event: SleepEvent, val sleep: SleepSummary?, val timeline: SleepTimeline, val unavailable: List<Stretch>) {

    /** The event's stretch with the audio of its sleep. */
    val audio: EventAudio get() = EventAudio(event.stretch, timeline, unavailable)
}

/**
 * The ones of [items] that their sleep's audio still reaches. A missing or damaged chunk file ends
 * a recording's audio there, so a sleep can lose the audio of an event or all of it, and then
 * neither a stretch nor a clock time can be found for the event.
 */
internal fun playable(items: List<ShareItem>): List<ShareItem> = items.filter { it.event.start < it.timeline.duration }

/**
 * The share step of events, each with the audio of its own sleep, run in [scope]: a single event
 * gets a stretch the user moves around it ([ShareStretch]), several events of one or more sleeps
 * one lead-in and tail for all ([ShareStretches]). A screen with a player passes whether it is
 * [playing], [pausePlayback], which stops it whenever the step opens, shares or closes, and
 * [listenTo], which plays or pauses a single event's stretch; without one the step offers no
 * listening. [onShared] follows once the files are written.
 */
class EventSharing(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val shares: EventShares,
    playing: Flow<Boolean> = flowOf(false),
    private val pausePlayback: () -> Unit = {},
    private val listenTo: ((Stretch) -> Unit)? = null,
    private val onShared: () -> Unit = {},
) {

    private val leadIn = settings.flow(Settings.leadIn).stateIn(scope, SharingStarted.Eagerly, Settings.leadIn.default)
    private val tail = settings.flow(Settings.tail).stateIn(scope, SharingStarted.Eagerly, Settings.tail.default)

    /** What the caption of a shared event names, as the user last chose it. */
    private val captionItems = settings.flow(Settings.shareCaption).stateIn(scope, SharingStarted.Eagerly, Settings.shareCaption.default)

    /** The open step; null while it is closed. */
    private val step = MutableStateFlow<Step?>(null)

    /** The open step, with whether its stretch is being listened to; null while it is closed. */
    val state: StateFlow<ShareUiState?> = combine(step, captionItems, leadIn, tail, playing) { step, captionItems, leadIn, tail, playing ->
        step?.let {
            val changed = it.stretch?.let { stretch -> stretch.selected != stretch.default }
                ?: it.stretches?.let { stretches -> stretches.leadIn != leadIn || stretches.tail != tail }
                ?: false
            ShareUiState(it.items, it.stretch, it.stretches, it.format, captionItems, changed, playing = playing, preparing = it.preparing)
        }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)

    private val sharedChannel = Channel<SharedFiles?>(Channel.BUFFERED)

    /** Each share's files ready for Android's share sheet, or null when they could not be written. */
    val shared: Flow<SharedFiles?> = sharedChannel.receiveAsFlow()

    /** Whether a single event's stretch can be listened to. */
    val canListen: Boolean get() = listenTo != null

    /**
     * Opens the step for [items] in their order, with the settings' lead-in and tail as cut
     * playback plays them: a single event's stretch can then be moved, several share one lead-in
     * and tail. Events whose sleep's audio no longer reaches them are left out; when none is left, [shared] reports
     * that nothing could be written.
     */
    fun open(items: List<ShareItem>) {
        val playable = playable(items)
        val opened = when (playable.size) {
            0 -> {
                sharedChannel.trySend(null)
                return
            }
            1 -> {
                val item = playable.single()
                val stretch = ShareStretch.of(item.event.stretch, item.timeline, leadIn.value, tail.value, item.unavailable)
                Step(playable, stretch, stretches = null)
            }
            else -> Step(playable, stretch = null, ShareStretches.of(playable.map { it.audio }, leadIn.value, tail.value))
        }
        pausePlayback()
        step.value = opened
    }

    /** Plays a single event's stretch as the user set it, or pauses it while it plays. */
    fun listen() {
        val selected = step.value?.stretch?.selected ?: return
        listenTo?.invoke(selected)
    }

    /** Shares the events as [format]. */
    fun setFormat(format: ShareFormat) {
        val step = step.value?.takeUnless { it.preparing } ?: return
        this.step.value = step.copy(format = format)
    }

    /** Names [item] in the caption ([on] true) or not, in this share and the next ones. */
    fun setCaptionItem(item: CaptionItem, on: Boolean) {
        val items = if (on) captionItems.value + item else captionItems.value - item
        scope.launch { settings.set(Settings.shareCaption, items) }
    }

    /** Moves the shared stretch of a single event to run from [start] until [end], as far as it may reach. */
    fun select(start: Duration, end: Duration) {
        val step = step.value?.takeUnless { it.preparing } ?: return
        val stretch = step.stretch ?: return
        this.step.value = step.copy(stretch = stretch.select(start, end))
    }

    /** Shares several events each with [leadIn] before and [tail] after it. */
    fun setMargins(leadIn: Duration, tail: Duration) {
        val step = step.value?.takeUnless { it.preparing } ?: return
        val stretches = step.stretches ?: return
        this.step.value = step.copy(stretches = ShareStretches.of(stretches.events, leadIn, tail))
    }

    /** Takes the shared stretches back to the events with the settings' lead-in and tail. */
    fun reset() {
        val step = step.value?.takeUnless { it.preparing } ?: return
        step.stretch?.let { this.step.value = step.copy(stretch = it.copy(selected = it.default)) }
        if (step.stretches != null) setMargins(leadIn.value, tail.value)
    }

    /**
     * Writes the shared stretches as files of the chosen format, one per event, closes the step and
     * reports them through [shared]. [labels] say what the card and the caption say about each
     * event, in the step's order, and the cards are drawn in [colors].
     */
    fun share(labels: List<EventLabels>, colors: CardColors) {
        val step = step.value?.takeUnless { it.preparing } ?: return
        val stretches = step.stretch?.let { listOf(it.selected) } ?: step.stretches?.selected ?: return
        this.step.value = step.copy(preparing = true)
        pausePlayback()
        scope.launch {
            val events = step.items.mapIndexed { i, item -> SharedEvent(item.timeline, stretches[i], item.event.start, labels[i]) }
            val uris = shares.write(events, step.format, colors)
            this@EventSharing.step.value = null
            if (uris != null) onShared()
            // Apps drop a text sent along with audio, so only a video gets a caption.
            val text = if (step.format == ShareFormat.VIDEO) caption(labels, captionItems.value) else null
            sharedChannel.send(uris?.let { SharedFiles(it, step.format, text) })
        }
    }

    /** Closes the step and stops listening to it, unless its files are being written. */
    fun close() {
        if (step.value?.preparing == true) return
        step.value = null
        pausePlayback()
    }

    /**
     * The open step: its events, the stretch of a single event or the stretches of several, the
     * format chosen so far and whether its files are being written.
     */
    private data class Step(
        val items: List<ShareItem>,
        val stretch: ShareStretch?,
        val stretches: ShareStretches?,
        val format: ShareFormat = ShareFormat.VIDEO,
        val preparing: Boolean = false,
    )
}

/**
 * [ShareSheet] for the open share [step] of [sharing], whose cards and caption name each event as
 * [shareLabels] words it with its own sleep. A single event's stretch is drawn over [loudness]
 * with the playhead at [position], and can be listened to where [sharing] has a player.
 */
@Composable
fun EventShareSheet(
    sharing: EventSharing,
    step: ShareUiState,
    loudness: SleepLoudness? = null,
    position: () -> Duration = { Duration.ZERO },
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val clock = LocalClockFormatter.current
    val labels = remember(step.items, clock, resources) {
        step.items.map { shareLabels(resources, clock, it.timeline, it.sleep, it.event) }
    }
    ShareSheet(
        state = step,
        labels = labels,
        loudness = loudness,
        position = position,
        onSelect = sharing::select,
        onMargins = sharing::setMargins,
        onReset = sharing::reset,
        onFormat = sharing::setFormat,
        onCaptionItem = sharing::setCaptionItem,
        onListen = if (sharing.canListen) sharing::listen else null,
        onShare = { sharing.share(labels, cardColors(context)) },
        onDismiss = sharing::close,
    )
}

/** Hands the written [files] to Android's share sheet. */
fun startShareChooser(context: Context, files: SharedFiles) {
    val resources = context.resources
    context.startActivity(EventShares.chooser(files.uris, files.format, files.caption, resources.getString(R.string.share_chooser)))
}
