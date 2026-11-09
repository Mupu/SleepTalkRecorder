package me.mupu.sleeptalkrecorder.detection

import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The labelled test nights under `.local/testdata/nights/` (see its README): `<night>.wav`, 16 kHz
 * mono 16-bit PCM, with its labels in `<night>.csv`. Used by the opt-in evaluations only.
 */
internal object TestNights {

    /** The names of the nights in [directory] that have both their audio and their labels, sorted. */
    fun names(directory: File): List<String> = directory.listFiles { file -> file.name.endsWith(".wav") }
        ?.map { it.nameWithoutExtension }
        ?.filter { File(directory, "$it.csv").isFile }
        ?.sorted()
        .orEmpty()

    /** The track of [wav], from [cache] when it holds the current format and is newer than the audio. */
    fun trackOf(wav: File, cache: File): FeatureTrack {
        val cached = if (cache.lastModified() > wav.lastModified()) FeatureTrackFile.read(cache) else null
        if (cached != null) return cached

        // Written beside the cache and renamed when complete, so a cancelled run leaves no partial track.
        cache.parentFile.mkdirs()
        val partial = File(cache.parentFile, cache.name + ".new")
        FeatureTrackRecorder(partial).use { features -> readPcm(wav) { samples, count -> features.push(samples, count) } }
        if (!partial.renameTo(cache) && !(cache.delete() && partial.renameTo(cache))) failure("cannot replace $cache")
        return FeatureTrackFile.read(cache) ?: failure("cannot read the extracted track $cache")
    }

    /** The labels of a night's CSV: `start_s,end_s,kind,...` with a header line. */
    fun labelsOf(csv: File): List<Label> = csv.readLines().drop(1).filter(String::isNotBlank).map { line ->
        val fields = line.split(',')
        Label(Span(fields[0].toDouble(), fields[1].toDouble()), fields[2])
    }

    /** The samples of [wav] from [span]'s start to its end in seconds, held within the file, from -1 to 1. */
    fun samples(wav: File, span: Span): FloatArray {
        val (offset, bytes) = dataChunk(wav)
        val frames = bytes / 2
        val from = (span.start * SAMPLE_RATE).toLong().coerceIn(0, frames)
        val until = (span.end * SAMPLE_RATE).toLong().coerceIn(from, frames)
        val data = ByteArray(((until - from) * 2).toInt())
        RandomAccessFile(wav, "r").use { file ->
            file.seek(offset + from * 2)
            file.readFully(data)
        }
        val pcm = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return FloatArray(data.size / 2) { pcm.get(it) / 32_768f }
    }

    /** Feeds the samples of [wav] to [onSamples] in buffers of a second. */
    private fun readPcm(wav: File, onSamples: (ShortArray, Int) -> Unit) {
        val (offset, dataBytes) = dataChunk(wav)
        DataInputStream(BufferedInputStream(wav.inputStream(), 1 shl 16)).use { input ->
            input.skipNBytes(offset)
            val bytes = ByteArray(SAMPLE_RATE * 2)
            val samples = ShortArray(SAMPLE_RATE)
            var remaining = dataBytes
            while (remaining >= 2) {
                val wanted = minOf(remaining, bytes.size.toLong()).toInt() and 1.inv()
                input.readFully(bytes, 0, wanted)
                ByteBuffer.wrap(bytes, 0, wanted).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples, 0, wanted / 2)
                onSamples(samples, wanted / 2)
                remaining -= wanted
            }
        }
    }

    /** Where the samples of [wav] start and how many bytes they take; "fmt " must say 16 kHz mono 16-bit PCM. */
    private fun dataChunk(wav: File): Pair<Long, Long> = RandomAccessFile(wav, "r").use { dataChunk(wav, it) }

    private fun dataChunk(wav: File, file: RandomAccessFile): Pair<Long, Long> {
        val riff = ByteArray(12).also(file::readFully)
        if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") failure("$wav is no WAV file")

        while (true) {
            if (file.filePointer + 8 > file.length()) failure("$wav has no data chunk")
            val header = ByteArray(8).also(file::readFully)
            val id = String(header, 0, 4, Charsets.US_ASCII)
            val size = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
            when (id) {
                "fmt " -> {
                    val format = ByteArray(size.toInt()).also(file::readFully)
                    val fields = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
                    val pcm16kMono16 = fields.getShort(0).toInt() == 1 && fields.getShort(2).toInt() == 1 &&
                        fields.getInt(4) == SAMPLE_RATE && fields.getShort(14).toInt() == 16
                    if (!pcm16kMono16) failure("$wav is not 16 kHz mono 16-bit PCM")
                }
                "data" -> return file.filePointer to size
                else -> file.seek(file.filePointer + size + size % 2)
            }
        }
    }

    fun failure(message: String): Nothing = throw AssertionError(message)

    const val SAMPLE_RATE = 16_000
}
