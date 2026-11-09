package me.mupu.sleeptalkrecorder.detection

import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.HOP_MILLIS
import me.mupu.sleeptalkrecorder.settings.Settings
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration
import java.util.Locale
import java.util.stream.Collectors
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Measures the [EventDetector] on the labelled test nights under `.local/testdata/nights/`: for
 * each night the share of speech-like labels an event found, the events that match no label and
 * how long cut playback would take. Prints a table and writes it as CSV to the output directory.
 *
 * Opt-in: the build passes the test data directory as the system property [TESTDATA] only when
 * the Gradle property `detectorEvaluation` is set (see README.md), so normal runs and CI skip it.
 * Feature tracks are extracted from the WAV files once and kept in the output directory.
 */
class DetectorEvaluationTest {

    @Test
    fun `measure the detector on the local test nights`() {
        val testdata = System.getProperty(TESTDATA)
        assumeTrue("set the Gradle property detectorEvaluation to run the detector evaluation", testdata != null)

        val nights = File(testdata!!, "nights")
        val names = nights.listFiles { file -> file.name.endsWith(".wav") }
            ?.map { it.nameWithoutExtension }
            ?.filter { File(nights, "$it.csv").isFile }
            ?.sorted()
            .orEmpty()
        if (names.isEmpty()) failure("no test nights (<night>.wav with <night>.csv) in $nights; see .local/testdata/README.md")

        val output = File(System.getProperty(OUTPUT) ?: failure("system property $OUTPUT missing"))
        val tracks = names.parallelStream()
            .map { name -> name to trackOf(File(nights, "$name.wav"), File(output, "tracks/$name.bin")) }
            .collect(Collectors.toList())
            .toMap()
        val labels = names.associateWith { labelsOf(File(nights, "$it.csv")) }

        val rows = PARAMETER_SETS.flatMap { (set, params) ->
            names.map { name ->
                val track = tracks.getValue(name)
                val detection = EventDetector.detect(track, params)
                val events = detection.events.map { Span(seconds(it.start), seconds(it.end)) }
                Row(set, name, seconds(track.size), detection.thresholdDb, NightScore.of(labels.getValue(name), events, seconds(track.size), LEAD_IN, TAIL))
            }
        }

        println(table(rows))
        output.mkdirs()
        File(output, "detector-evaluation.csv").writeText(csv(rows))
        println("Written to ${File(output, "detector-evaluation.csv")}")
    }

    private data class Row(val set: String, val night: String, val seconds: Double, val thresholdDb: Float, val score: NightScore)

    /** The track of [wav], from [cache] when it holds the current format and is newer than the audio. */
    private fun trackOf(wav: File, cache: File): FeatureTrack {
        val cached = if (cache.lastModified() > wav.lastModified()) FeatureTrackFile.read(cache) else null
        if (cached != null) return cached

        // Written beside the cache and renamed when complete, so a cancelled run leaves no partial track.
        val partial = File(cache.parentFile, cache.name + ".new")
        FeatureTrackRecorder(partial).use { features -> readPcm(wav) { samples, count -> features.push(samples, count) } }
        if (!partial.renameTo(cache) && !(cache.delete() && partial.renameTo(cache))) failure("cannot replace $cache")
        return FeatureTrackFile.read(cache) ?: failure("cannot read the extracted track $cache")
    }

    /** Feeds the samples of a 16 kHz mono 16-bit PCM WAV file to [onSamples] in buffers of a second. */
    private fun readPcm(wav: File, onSamples: (ShortArray, Int) -> Unit) {
        DataInputStream(BufferedInputStream(wav.inputStream(), 1 shl 16)).use { input ->
            val riff = ByteArray(12).also(input::readFully)
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF" || String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") failure("$wav is no WAV file")

            // Walks the chunks to "data"; "fmt " must say 16 kHz mono 16-bit PCM.
            var dataBytes = -1L
            while (dataBytes < 0) {
                val header = ByteArray(8)
                try {
                    input.readFully(header)
                } catch (_: EOFException) {
                    failure("$wav has no data chunk")
                }
                val id = String(header, 0, 4, Charsets.US_ASCII)
                val size = ByteBuffer.wrap(header, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFF_FFFFL
                when (id) {
                    "fmt " -> {
                        val format = ByteArray(size.toInt()).also(input::readFully)
                        val fields = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
                        val pcm16kMono16 = fields.getShort(0).toInt() == 1 && fields.getShort(2).toInt() == 1 &&
                            fields.getInt(4) == 16_000 && fields.getShort(14).toInt() == 16
                        if (!pcm16kMono16) failure("$wav is not 16 kHz mono 16-bit PCM")
                    }
                    "data" -> dataBytes = size
                    else -> input.skipNBytes(size + size % 2)
                }
            }

            val bytes = ByteArray(16_000 * 2)
            val samples = ShortArray(16_000)
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

    /** The labels of a night's CSV: `start_s,end_s,kind,...` with a header line. */
    private fun labelsOf(csv: File): List<Label> = csv.readLines().drop(1).filter(String::isNotBlank).map { line ->
        val fields = line.split(',')
        Label(Span(fields[0].toDouble(), fields[1].toDouble()), fields[2])
    }

    private fun table(rows: List<Row>): String = buildString {
        val kinds = NightScore.SPEECH_LIKE
        appendLine()
        appendLine("Speech-like labels found (+/-1 s), false events (matching no label) and cut playback (lead-in ${LEAD_IN}s, tail ${TAIL}s)")
        for ((set, ofSet) in rows.groupBy { it.set }) {
            appendLine()
            appendLine("Parameter set: $set")
            val header = listOf("night", "hours", "thr dB", "events", "speech found") + kinds + listOf("false", "cut min")
            val lines = ofSet.map { row ->
                val score = row.score
                listOf(
                    row.night,
                    "%.1f".format(Locale.ROOT, row.seconds / 3600),
                    "%.1f".format(Locale.ROOT, row.thresholdDb),
                    score.events.toString(),
                    found(score.speech),
                ) + kinds.map { kind -> score.speechByKind[kind]?.let(::found) ?: "-" } + listOf(
                    score.falseEvents.toString(),
                    "%.1f".format(Locale.ROOT, score.cutSeconds / 60),
                )
            }
            val widths = header.indices.map { column -> (lines.map { it[column] } + header[column]).maxOf { it.length } }
            for (line in listOf(header) + lines) {
                appendLine(line.mapIndexed { column, cell -> if (column == 0) cell.padEnd(widths[column]) else cell.padStart(widths[column]) }.joinToString("  "))
            }
        }
    }

    private fun found(score: KindScore) = "${score.found}/${score.labels} ${"%.0f".format(Locale.ROOT, 100 * (score.share ?: 0.0))}%"

    /** One line per parameter set, night and speech-like kind (`all` for all of them together). */
    private fun csv(rows: List<Row>): String = buildString {
        appendLine("parameter_set,night,hours,threshold_db,events,false_events,cut_minutes,kind,labels,found,share")
        for (row in rows) {
            val score = row.score
            for ((kind, kindScore) in listOf("all" to score.speech) + score.speechByKind.toList()) {
                appendLine(
                    listOf(
                        row.set, row.night,
                        "%.3f".format(Locale.ROOT, row.seconds / 3600),
                        "%.1f".format(Locale.ROOT, row.thresholdDb),
                        score.events, score.falseEvents,
                        "%.2f".format(Locale.ROOT, score.cutSeconds / 60),
                        kind, kindScore.labels, kindScore.found,
                        kindScore.share?.let { "%.3f".format(Locale.ROOT, it) }.orEmpty(),
                    ).joinToString(","),
                )
            }
        }
    }

    private fun failure(message: String): Nothing = throw AssertionError(message)

    private fun seconds(hops: Int): Double = hops * HOP_MILLIS / 1000.0

    private companion object {
        /** The test data directory, the one holding `nights/`; set by the build. */
        const val TESTDATA = "detectorEvaluation.testdata"

        /** Where the table, the CSV and the cached feature tracks go; set by the build. */
        const val OUTPUT = "detectorEvaluation.output"

        val LEAD_IN = Settings.leadIn.default.toMillis() / 1000.0
        val TAIL = Settings.tail.default.toMillis() / 1000.0

        /**
         * The detector's defaults and alternatives that each move one knob, so every run shows what
         * the defaults trade against finding more or producing fewer events.
         */
        val PARAMETER_SETS: Map<String, DetectionParams> = linkedMapOf(
            "default" to DetectionParams(),
            "minimum length 500 to 150 ms" to DetectionParams(
                minLengthAtLeastSensitive = Duration.ofMillis(500),
                minLengthAtMostSensitive = Duration.ofMillis(150),
            ),
            "hysteresis share 0.6" to DetectionParams(hysteresisShare = 0.6f),
            "sensitivity 0.65" to DetectionParams(sensitivity = 0.65f),
            "baseline percentile 0.1" to DetectionParams(baselinePercentile = 0.1f),
        )
    }
}
