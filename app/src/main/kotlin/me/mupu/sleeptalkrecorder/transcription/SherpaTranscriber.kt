package me.mupu.sleeptalkrecorder.transcription

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * A [TranscriptionModel] loaded into sherpa-onnx's offline recognizer, which keeps the model in
 * memory (about 1 GB for Parakeet) until it is closed.
 *
 * Whisper is given the user's [language]: Whisper with one fixed language translates or garbles
 * speech in another one, so it first detects the language itself and is asked again with the
 * user's language only when it detected neither that nor English. (sherpa-onnx 2.0.1 cannot limit
 * the detection to those two.) Parakeet recognises its 25 languages without being told.
 *
 * The model is loaded and run on a [thread] of its own at background priority, which the
 * recognizer's worker threads take over when it creates them, so a run on all cores never slows
 * the screen down: the system gives the app's main and render threads the cores first.
 */
class SherpaTranscriber private constructor(
    private val recognizer: OfflineRecognizer,
    private val language: String?,
    private val thread: ExecutorService,
) : Transcriber {

    override fun transcribe(samples: FloatArray, sampleRate: Int): String = thread.await { recognise(samples, sampleRate) }

    private fun recognise(samples: FloatArray, sampleRate: Int): String {
        val (text, detected) = decode(samples, sampleRate)
        val whisper = recognizer.config.modelConfig.whisper
        if (language == null || whisper.encoder.isEmpty() || detected in setOf(language, ENGLISH)) return text

        recognizer.setConfig(recognizer.config.copy(modelConfig = recognizer.config.modelConfig.copy(whisper = whisper.copy(language = language))))
        try {
            return decode(samples, sampleRate).first
        } finally {
            recognizer.setConfig(recognizer.config.copy(modelConfig = recognizer.config.modelConfig.copy(whisper = whisper)))
        }
    }

    /** The text and the language the model detected, if it tells, without Whisper's `<|…|>`. */
    private fun decode(samples: FloatArray, sampleRate: Int): Pair<String, String> {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            return result.text to result.lang.removePrefix("<|").removeSuffix("|>")
        } finally {
            stream.release()
        }
    }

    override fun close() {
        try {
            thread.await { recognizer.release() }
        } finally {
            thread.shutdown()
        }
    }

    companion object {
        private const val ENGLISH = "en"

        /**
         * Java's thread priority that Android runs at its background priority (nice 10,
         * `Process.THREAD_PRIORITY_BACKGROUND`); set through Java, it also works where the model
         * runs on a computer for the evaluation.
         */
        private const val BACKGROUND_PRIORITY = 4

        /** A single thread at [BACKGROUND_PRIORITY] for loading and running a model. */
        private fun backgroundThread(): ExecutorService = Executors.newSingleThreadExecutor { task ->
            Thread(task, "SpeechRecognition").apply { priority = BACKGROUND_PRIORITY }
        }

        /** Runs [block] on this executor and waits for it, throwing what it throws. */
        private fun <T> ExecutorService.await(block: () -> T): T = try {
            submit(Callable(block)).get()
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }

        /**
         * Loads [model] from the [directory] its files are installed in; [language] is the
         * user's language code, which only Whisper uses.
         *
         * @throws IllegalArgumentException when sherpa-onnx cannot load the files.
         */
        fun load(model: TranscriptionModel, directory: File, language: String?): SherpaTranscriber {
            fun file(name: String) = File(directory, name).path
            // Speed grows little beyond four threads, and the phone stays responsive.
            val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            val config = when (model) {
                TranscriptionModel.PARAKEET_ULTRA -> OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = file("encoder.int8.onnx"),
                        decoder = file("decoder.int8.onnx"),
                        joiner = file("joiner.int8.onnx"),
                    ),
                    tokens = file("tokens.txt"),
                    modelType = "nemo_transducer",
                    numThreads = threads,
                )
                TranscriptionModel.WHISPER_SMALL -> OfflineModelConfig(
                    // An empty language makes Whisper detect it; the Kotlin default is English.
                    whisper = OfflineWhisperModelConfig(
                        encoder = file("small-encoder.int8.onnx"),
                        decoder = file("small-decoder.int8.onnx"),
                        language = "",
                        task = "transcribe",
                    ),
                    tokens = file("small-tokens.txt"),
                    numThreads = threads,
                )
            }
            val thread = backgroundThread()
            val recognizer = try {
                thread.await { OfflineRecognizer(null, OfflineRecognizerConfig(modelConfig = config)) }
            } catch (failed: Throwable) {
                thread.shutdown()
                throw failed
            }
            return SherpaTranscriber(recognizer, language.takeIf { model.needsLanguage }, thread)
        }
    }
}
