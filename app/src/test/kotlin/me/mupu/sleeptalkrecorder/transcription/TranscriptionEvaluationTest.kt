package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.EventKinds
import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.detection.FeatureTrack.Companion.HOP_MILLIS
import me.mupu.sleeptalkrecorder.detection.Label
import me.mupu.sleeptalkrecorder.detection.NightScore
import me.mupu.sleeptalkrecorder.detection.Span
import me.mupu.sleeptalkrecorder.detection.TestNights
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.playback.Stretch
import java.io.File
import java.time.Duration
import java.util.Locale
import java.util.stream.Collectors
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Runs the speech recognition pipeline of the app (events from the detector, their clips, the
 * model, the filters) over a sample of the events of the labelled test nights, and reports how
 * often it finds words where nothing was said, and what it makes of speech.
 *
 * Opt-in: the build passes the test data and the model directory only when the Gradle properties
 * `transcriptionEvaluation` and `transcriptionModel` are set (see README.md). The model runs on the
 * computer with sherpa-onnx's desktop library of the same release as the app's.
 *
 * A whole run over every event would take far longer than five minutes, so each night gives a
 * fixed sample spread evenly over its events, and the run stops sampling after [TIME_BUDGET].
 */
class TranscriptionEvaluationTest {

    /** What the labels say an event is. */
    private enum class Group {
        /** It overlaps a label in which something is said ([NightScore.SPEECH_LIKE]). */
        SPEECH,

        /** It overlaps a vocal sound that may or may not hold words, and no speech. */
        VOCAL,

        /** Anything else, also sounds no label covers. */
        NON_SPEECH,
    }

    private data class Result(val night: String, val event: Span, val group: Group, val labels: List<String>, val verdict: String, val text: String?)

    @Test
    fun `run speech recognition over the events of the local test nights`() {
        val testdata = System.getProperty(TESTDATA)
        assumeTrue("set the Gradle property transcriptionEvaluation to run the transcription evaluation", testdata != null)

        val nights = File(testdata!!, "nights")
        val names = TestNights.names(nights)
        if (names.isEmpty()) TestNights.failure("no test nights (<night>.wav with <night>.csv) in $nights; see .local/testdata/README.md")
        val model = File(System.getProperty(MODEL) ?: TestNights.failure("system property $MODEL missing"))
        val output = File(System.getProperty(OUTPUT) ?: TestNights.failure("system property $OUTPUT missing"))
        val tracks = File(System.getProperty(TRACKS) ?: TestNights.failure("system property $TRACKS missing"), "tracks")

        // The desktop library needs ONNX Runtime from beside it, not an older one Windows may hold.
        File(System.getProperty(LIBRARIES)).listFiles { file -> file.name.contains("onnxruntime") }
            ?.forEach { System.load(it.absolutePath) }

        // Extracting missing tracks takes a minute or two, once; it is not part of the time budget.
        val nightTracks = names.parallelStream()
            .map { name -> name to TestNights.trackOf(File(nights, "$name.wav"), File(tracks, "$name.bin")) }
            .collect(Collectors.toList())
            .toMap()

        val loading = System.nanoTime()
        val recognition = SpeechRecognition(TranscriptionModel.PARAKEET_ULTRA, SherpaTranscriber.load(TranscriptionModel.PARAKEET_ULTRA, model, null))
        println("Model loaded in ${(System.nanoTime() - loading) / 1_000_000} ms")

        val started = System.nanoTime()
        var audioSeconds = 0.0
        val results = ArrayList<Result>()
        recognition.use {
            for (name in names) {
                val wav = File(nights, "$name.wav")
                val labels = TestNights.labelsOf(File(nights, "$name.csv"))
                val track = nightTracks.getValue(name)
                val events = EventDetector.detect(track).events.map { Span(it.start * HOP_MILLIS / 1000.0, it.end * HOP_MILLIS / 1000.0) }
                val byGroup = events.groupBy { event -> groupOf(event, labels) }

                for ((group, count) in SAMPLE) {
                    for (event in spread(byGroup[group].orEmpty(), count)) {
                        if (System.nanoTime() - started > TIME_BUDGET.toNanos()) break
                        val clipSpan = Span(event.start - SpeechRecognition.PADDING.toMillis() / 1000.0, event.end + SpeechRecognition.PADDING.toMillis() / 1000.0)
                        val samples = TestNights.samples(wav, clipSpan)
                        audioSeconds += samples.size / TestNights.SAMPLE_RATE.toDouble()
                        val stretch = Stretch(Duration.ofMillis((event.start * 1000).toLong()), Duration.ofMillis((event.end * 1000).toLong()))
                        val transcript = runBlocking { recognition.analyse(EventClip(0, stretch, samples, TestNights.SAMPLE_RATE)) }.single()
                        val verdict = when {
                            transcript.kindId == EventKinds.EVENT -> "none"
                            transcript.text == null -> "murmur"
                            else -> "words"
                        }
                        results += Result(name, event, group, labels.filter { overlaps(event, it) }.map { it.kind }.distinct(), verdict, transcript.text)
                    }
                }
            }
        }
        val seconds = (System.nanoTime() - started) / 1e9

        println(report(results, seconds, audioSeconds))
        output.mkdirs()
        File(output, "transcription-evaluation.csv").writeText(csv(results))
        println("Written to ${File(output, "transcription-evaluation.csv")}")
    }

    private fun groupOf(event: Span, labels: List<Label>): Group {
        val kinds = labels.filter { overlaps(event, it) }.map { it.kind }
        return when {
            kinds.any { it in NightScore.SPEECH_LIKE } -> Group.SPEECH
            kinds.any { it in VOCAL } -> Group.VOCAL
            else -> Group.NON_SPEECH
        }
    }

    /** As the detector evaluation counts them: overlapping or at most a second apart. */
    private fun overlaps(event: Span, label: Label) = event.start <= label.span.end + 1.0 && label.span.start <= event.end + 1.0

    /** [count] of [events], spread evenly over them, so every part of the night is sampled. */
    private fun <T> spread(events: List<T>, count: Int): List<T> =
        if (events.size <= count) events else (0 until count).map { events[it * events.size / count] }

    private fun report(results: List<Result>, seconds: Double, audioSeconds: Double): String = buildString {
        appendLine()
        appendLine("Speech recognition over sampled events (Parakeet, app filters). Verdicts: words / murmur / none")
        val header = listOf("night", "speech", "vocal", "non-speech", "words on non-speech")
        val rows = results.groupBy { it.night }.map { (night, ofNight) ->
            listOf(night) + Group.entries.map { counts(ofNight.filter { r -> r.group == it }) } +
                share(ofNight.filter { it.group == Group.NON_SPEECH })
        } + listOf(listOf("all") + Group.entries.map { counts(results.filter { r -> r.group == it }) } + share(results.filter { it.group == Group.NON_SPEECH }))
        val widths = header.indices.map { column -> (rows.map { it[column] } + header[column]).maxOf { it.length } }
        for (row in listOf(header) + rows) {
            appendLine(row.mapIndexed { column, cell -> if (column == 0) cell.padEnd(widths[column]) else cell.padStart(widths[column]) }.joinToString("  "))
        }
        appendLine()
        appendLine("Words on non-speech events:")
        results.filter { it.group == Group.NON_SPEECH && it.verdict == "words" }.forEach {
            appendLine("  ${it.night} ${"%.1f".format(Locale.ROOT, it.event.start)} s ${it.labels}: \"${it.text}\"")
        }
        appendLine()
        appendLine("Words on speech events:")
        results.filter { it.group == Group.SPEECH && it.verdict == "words" }.forEach {
            appendLine("  ${it.night} ${"%.1f".format(Locale.ROOT, it.event.start)} s ${it.labels}: \"${it.text}\"")
        }
        appendLine()
        appendLine(
            "${results.size} events, ${"%.0f".format(Locale.ROOT, audioSeconds)} s of audio in ${"%.0f".format(Locale.ROOT, seconds)} s " +
                "(real-time factor ${"%.2f".format(Locale.ROOT, seconds / audioSeconds)})" +
                if (seconds * 1e9 > TIME_BUDGET.toNanos()) "; stopped sampling after ${TIME_BUDGET.toMinutes()} min" else "",
        )
    }

    private fun counts(results: List<Result>) =
        listOf("words", "murmur", "none").joinToString(" / ") { verdict -> results.count { it.verdict == verdict }.toString() }

    private fun share(results: List<Result>) =
        "${results.count { it.verdict == "words" }}/${results.size} ${"%.0f".format(Locale.ROOT, 100.0 * results.count { it.verdict == "words" } / results.size.coerceAtLeast(1))}%"

    private fun csv(results: List<Result>): String = buildString {
        appendLine("night,start_s,end_s,group,labels,verdict,text")
        for (r in results) {
            val text = r.text.orEmpty().replace("\"", "\"\"")
            appendLine("${r.night},${"%.3f".format(Locale.ROOT, r.event.start)},${"%.3f".format(Locale.ROOT, r.event.end)},${r.group},${r.labels.joinToString(" ")},${r.verdict},\"$text\"")
        }
    }

    private companion object {
        const val TESTDATA = "transcriptionEvaluation.testdata"
        const val MODEL = "transcriptionEvaluation.model"
        const val LIBRARIES = "transcriptionEvaluation.libraries"

        /** Where the CSV goes; set by the build. */
        const val OUTPUT = "transcriptionEvaluation.output"

        /** The detector evaluation's output, whose feature tracks are reused; set by the build. */
        const val TRACKS = "detectorEvaluation.output"

        /** Vocal sounds of the test nights that may hold words. */
        val VOCAL = listOf("sleep_vocal", "night_terror")

        /** How many events of each group a night gives at most. */
        val SAMPLE = mapOf(Group.SPEECH to 8, Group.VOCAL to 4, Group.NON_SPEECH to 20)

        /**
         * The run stops sampling after this, so that with the model loaded and the tracks
         * extracted it stays below five minutes.
         */
        val TIME_BUDGET: Duration = Duration.ofMinutes(3)
    }
}
