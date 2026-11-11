package me.mupu.sleeptalkrecorder.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import me.mupu.sleeptalkrecorder.playback.CutPlan
import me.mupu.sleeptalkrecorder.playback.SleepAudio
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.io.File
import java.io.IOException
import java.time.Duration
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One event to share: the [stretch] of its sleep's audio, read from that sleep's [timeline], where
 * the event starts, and what its card says.
 */
class SharedEvent(val timeline: SleepTimeline, val stretch: Stretch, val eventStart: Duration, val labels: EventLabels)

/**
 * Turns stretches of a sleep into files that other apps receive through Android's share sheet:
 * one per event, a video with the event's card ([ShareFormat.VIDEO]) or its audio alone. The files
 * lie in the cache, in a folder that only the newest share occupies, and are handed out through the
 * app's [FileProvider], which grants the receiving app read access to them alone.
 */
class EventShares(private val context: Context) {

    private val directory = File(context.cacheDir, DIRECTORY)

    /**
     * Writes one file of [format] for each of [events], each from the audio of its own sleep, named
     * after the event's clock time and, of several, its number, its card drawn in [colors] in the
     * shape that a messenger's album of that many videos shows whole, and returns their content
     * URIs in the same order; null when the audio cannot be read or encoded. The files shared
     * before are deleted first: a receiving app has read them by the time the user shares again.
     */
    suspend fun write(events: List<SharedEvent>, format: ShareFormat, colors: CardColors): List<Uri>? =
        withContext(Dispatchers.IO) {
            try {
                directory.listFiles()?.forEach { it.delete() }
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")

                val shapes = cardShapes(events.size)
                events.mapIndexed { index, event ->
                    val timeline = event.timeline
                    val sampleRate = SleepAudio.sampleRateOf(timeline)
                    val name = shareFileName(timeline.instantAt(event.eventStart), ZoneId.systemDefault(), format, index, events.size)
                    val file = File(directory, name)
                    val pcm = SleepAudio(timeline).use { it.pcm(event.stretch) }
                    fadeEdges(pcm, CutPlan.fadeFrames(sampleRate).toInt())
                    val audio = Mp4Encoder.audio(pcm, sampleRate)
                    val tracks = when (format) {
                        ShareFormat.AUDIO -> listOf(audio)
                        ShareFormat.VIDEO -> {
                            val card = ShareCard.draw(event.labels, colors, shapes[index], shareNumber(index, events.size))
                            val lengthUs = pcm.size * MICROS_PER_SECOND / sampleRate
                            listOf(Mp4Encoder.still(card, lengthUs).also { card.recycle() }, audio)
                        }
                    }
                    Mp4Encoder.write(file, tracks)
                    FileProvider.getUriForFile(context, authority(context), file)
                }
            } catch (error: IOException) {
                Log.w(TAG, "Cannot write the shared files", error)
                null
            } catch (error: IllegalStateException) {
                Log.w(TAG, "Cannot encode the shared files", error)
                null
            } catch (error: IllegalArgumentException) {
                // MediaCodec refuses a format it cannot configure with this.
                Log.w(TAG, "Cannot encode the shared files", error)
                null
            }
        }

    companion object {
        /** The folder of the cache that holds the shared files; `res/xml/shared_files.xml` names it too. */
        private const val DIRECTORY = "shared"

        private const val TAG = "EventShares"

        private const val MICROS_PER_SECOND = 1_000_000L

        /** The [FileProvider]'s authority, which the manifest declares for each build's application id. */
        private fun authority(context: Context) = "${context.packageName}.share"

        /**
         * The share sheet for the files at [uris] of [format], whose recipient may read them, with
         * [caption] as the text sent along. Several files go in one `ACTION_SEND_MULTIPLE`, which
         * carries one text for all of them.
         */
        fun chooser(uris: List<Uri>, format: ShareFormat, caption: String?, title: CharSequence): Intent {
            val type = when (format) {
                ShareFormat.VIDEO -> "video/mp4"
                ShareFormat.AUDIO -> "audio/mp4"
            }
            val send = if (uris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.single())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            }
            send.setType(type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (caption != null) send.putExtra(Intent.EXTRA_TEXT, caption)
            // The share sheet shows the files from the clip and passes on the read grant with it.
            send.clipData = ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            return Intent.createChooser(send, title)
        }
    }
}

/**
 * Fades [pcm] in over its first and out over its last [frames] samples, as cut playback does, so
 * the shared file neither starts nor ends with a click.
 */
internal fun fadeEdges(pcm: ShortArray, frames: Int) {
    val fade = minOf(frames, pcm.size / 2)
    for (i in 0 until fade) {
        val gain = i.toFloat() / fade
        pcm[i] = (pcm[i] * gain).toInt().toShort()
        pcm[pcm.size - 1 - i] = (pcm[pcm.size - 1 - i] * gain).toInt().toShort()
    }
}
