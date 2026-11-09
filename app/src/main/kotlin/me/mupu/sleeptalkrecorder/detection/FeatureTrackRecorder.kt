package me.mupu.sleeptalkrecorder.detection

import java.io.Closeable
import java.io.File

/**
 * Writes the [FeatureTrack] of a recording into [file] while the audio is recorded, so finding
 * events after the stop never decodes the audio. The file is created with the first completed
 * hop; storage errors surface as `IOException` from [push], [flush] and [close].
 */
class FeatureTrackRecorder(private val file: File) : Closeable {

    private var writer: FeatureTrackFile.Writer? = null
    private val extractor = FeatureExtractor { record -> open().write(record) }

    /** Adds the first [count] samples of [samples]. */
    fun push(samples: ShortArray, count: Int) {
        extractor.push(samples, count)
    }

    /** Makes every completed hop durable. */
    fun flush() {
        writer?.flush()
    }

    /** Finishes the file; samples of an incomplete last hop are left out. */
    override fun close() {
        writer?.close()
        writer = null
    }

    private fun open(): FeatureTrackFile.Writer = writer ?: FeatureTrackFile.Writer(file.apply { parentFile?.mkdirs() }).also { writer = it }
}
