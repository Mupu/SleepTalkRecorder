package me.mupu.sleeptalkrecorder.ui.share

import android.net.Uri
import me.mupu.sleeptalkrecorder.share.CaptionItem
import me.mupu.sleeptalkrecorder.share.ShareFormat
import me.mupu.sleeptalkrecorder.share.ShareStretch
import me.mupu.sleeptalkrecorder.share.ShareStretches

/** The share step of [items] while it is open: of a single event, or of several at once. */
data class ShareUiState(
    /** The events shared, each with its sleep, in the order their files and caption lines follow. */
    val items: List<ShareItem>,
    /** For a single event, the stretch shared and how far it can be moved; null for several. */
    val stretch: ShareStretch?,
    /** For several events, the stretch shared of each with one lead-in and tail; null for one. */
    val stretches: ShareStretches?,
    val format: ShareFormat,
    /** What the caption names, which only a video gets. */
    val caption: Set<CaptionItem>,
    /** Whether the stretches differ from the events with the settings' lead-in and tail, so they can be reset. */
    val changed: Boolean,
    /** Whether the player plays, which only listening to the stretch can while the step is open. */
    val playing: Boolean,
    /** Whether the audio file is being written. */
    val preparing: Boolean,
)

/** Files written for Android's share sheet, of [format], with the [caption] sent along, if any. */
class SharedFiles(val uris: List<Uri>, val format: ShareFormat, val caption: String?)
