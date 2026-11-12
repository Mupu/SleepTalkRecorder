package me.mupu.sleeptalkrecorder.detection

import com.k2fsa.sherpa.onnx.AudioTagging
import com.k2fsa.sherpa.onnx.AudioTaggingConfig
import com.k2fsa.sherpa.onnx.AudioTaggingModelConfig
import java.io.File

/**
 * A CED model (an AudioSet tagger, Apache-2.0 weights) run by sherpa-onnx's `AudioTagging` from the
 * two files of its sherpa-onnx release: `model.int8.onnx` and `class_labels_indices.csv`. It
 * computes CED's filterbank itself and gives all 527 classes for every clip, which may be from
 * about 0.16 s to 30 s long. It holds the loaded model until it is closed.
 */
class CedTagger private constructor(
    private val tagging: AudioTagging,
    override val classNames: List<String>,
) : SoundTagger {

    override fun tag(samples: FloatArray, sampleRate: Int): FloatArray {
        val probabilities = FloatArray(classNames.size)
        val stream = tagging.createStream()
        try {
            stream.acceptWaveform(samples, sampleRate)
            for (event in tagging.compute(stream, classNames.size)) {
                if (event.index in probabilities.indices) probabilities[event.index] = event.prob
            }
        } finally {
            stream.release()
        }
        return probabilities
    }

    override fun close() = tagging.release()

    companion object {
        /** The model file of a CED release. */
        const val MODEL_FILE = "model.int8.onnx"

        /** The file with the names of its classes. */
        const val LABELS_FILE = "class_labels_indices.csv"

        /**
         * Loads the model from the [directory] its files are installed in, run on [threads] threads.
         *
         * @throws IllegalArgumentException when sherpa-onnx cannot load the files or the labels are unreadable.
         */
        fun load(directory: File, threads: Int = 1): CedTagger {
            val labels = File(directory, LABELS_FILE)
            val names = try {
                classNames(labels.readLines())
            } catch (error: java.io.IOException) {
                throw IllegalArgumentException("Cannot read $labels", error)
            }
            require(names.isNotEmpty()) { "No classes in $labels" }
            val file = File(directory, MODEL_FILE)
            require(file.isFile) { "No model at $file" }
            val model = AudioTaggingModelConfig(ced = file.path, numThreads = threads)
            return CedTagger(AudioTagging(null, AudioTaggingConfig(model = model, labels = labels.path, topK = names.size)), names)
        }

        /**
         * The display names of the [lines] of `class_labels_indices.csv` (`index,mid,"display name"`)
         * in index order; the header and lines that cannot be read are left out.
         */
        fun classNames(lines: List<String>): List<String> = lines.drop(1).mapNotNull { line ->
            val fields = line.split(',', limit = 3)
            if (fields.size < 3 || fields[0].trim().toIntOrNull() == null) null else fields[2].trim().removeSurrounding("\"")
        }
    }
}
