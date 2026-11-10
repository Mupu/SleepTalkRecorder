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
 * Turns a stretch of a sleep into an M4A file that other apps receive through Android's share
 * sheet. The file lies in the cache, in a folder that only the newest shared file occupies, and is
 * handed out through the app's [FileProvider], which grants the receiving app read access to that
 * one file.
 */
class EventShares(private val context: Context) {

    private val directory = File(context.cacheDir, DIRECTORY)

    /**
     * Writes [stretch] of the sleep [timeline] as an M4A file named after [eventStart], the event's
     * position, and returns its content URI; null when its audio cannot be read or encoded.
     * The file shared before is deleted first: a receiving app has read it by the time the user
     * shares the next one.
     */
    suspend fun write(timeline: SleepTimeline, stretch: Stretch, eventStart: Duration): Uri? = withContext(Dispatchers.IO) {
        try {
            directory.listFiles()?.forEach { it.delete() }
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("cannot create $directory")

            val file = File(directory, shareFileName(timeline.instantAt(eventStart), ZoneId.systemDefault()))
            val sampleRate = SleepAudio.sampleRateOf(timeline)
            val pcm = SleepAudio(timeline).use { it.pcm(stretch) }
            fadeEdges(pcm, CutPlan.fadeFrames(sampleRate).toInt())
            M4aEncoder.encode(pcm, sampleRate, file)
            FileProvider.getUriForFile(context, authority(context), file)
        } catch (error: IOException) {
            Log.w(TAG, "Cannot write the shared audio of $stretch", error)
            null
        } catch (error: IllegalStateException) {
            Log.w(TAG, "Cannot encode the shared audio of $stretch", error)
            null
        }
    }

    companion object {
        /** The folder of the cache that holds the shared file; `res/xml/shared_files.xml` names it too. */
        private const val DIRECTORY = "shared"

        private const val TAG = "EventShares"

        /** The [FileProvider]'s authority, which the manifest declares for each build's application id. */
        private fun authority(context: Context) = "${context.packageName}.share"

        /** The share sheet for the M4A file at [uri], whose recipient may read it. */
        fun chooser(uri: Uri, title: CharSequence): Intent {
            val send = Intent(Intent.ACTION_SEND)
                .setType(MIME_TYPE)
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // The share sheet shows the file from the clip and passes on the read grant with it.
            send.clipData = ClipData.newRawUri(null, uri)
            return Intent.createChooser(send, title)
        }

        private const val MIME_TYPE = "audio/mp4"
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
