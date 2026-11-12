package me.mupu.sleeptalkrecorder.models

/**
 * A model the app downloads into its own directory: a [TranscriptionModel] or a [SoundModel].
 *
 * Each is a set of single files from the Hugging Face repository the sherpa-onnx project
 * publishes it in, pinned to one commit and checked against the SHA-256 of each file, so a file
 * changed upstream can never be installed by accident. Single files instead of the project's
 * `.tar.bz2` release archives need no decompressor and no room for an archive next to its
 * contents, and a download can resume file by file.
 */
sealed interface DownloadableModel {
    /** The directory name of the installed files; never changes once a release stored a model. */
    val id: String

    /** The name shown in the settings. */
    val displayName: String

    /** The files the model consists of; all of them make an installed model. */
    val files: List<ModelFile>

    /** The size of all files in bytes, which is both the download and the space it takes. */
    val bytes: Long

    /** The enum constant's name, by which download jobs name the model. */
    val name: String

    companion object {
        /** Every model the app can download. */
        val all: List<DownloadableModel> = TranscriptionModel.entries + SoundModel.entries

        /** The model whose constant is called [name]; null if there is none. */
        fun named(name: String?): DownloadableModel? = all.find { it.name == name }
    }
}

/**
 * A sound tagger the second way of finding events runs with sherpa-onnx (`detection.SoundSearch`):
 * CED, an AudioSet tagger whose weights are Apache-2.0, in three sizes, as the int8 files of the
 * sherpa-onnx releases. CED-tiny listens through the whole night; CED-mini sorts what was found,
 * and CED-small can sort instead for a comparison in the developer settings.
 */
enum class SoundModel(override val displayName: String, override val files: List<ModelFile>) : DownloadableModel {
    /** CED-tiny, 5.5 M parameters: fast enough to hear every 2 s of a night. */
    CED_TINY(
        displayName = "CED-tiny",
        files = huggingFace(
            repository = "k2-fsa/sherpa-onnx-ced-tiny-audio-tagging-2024-04-19",
            commit = "efe3a5cfad74e56598d855d055006903adc00e38",
            "model.int8.onnx" to 6_133_417L sha "73aa22e783115f6a4ae169b36089907e07d0fd44795595eddea9c1bfc74cc945",
            LABELS,
        ),
    ),

    /** CED-mini, 9.6 M parameters: sorts each candidate, as good as CED-small at half its time. */
    CED_MINI(
        displayName = "CED-mini",
        files = huggingFace(
            repository = "k2-fsa/sherpa-onnx-ced-mini-audio-tagging-2024-04-19",
            commit = "4da6df50dc47b25f91074b5ef7df4c1cc0dd7d4d",
            "model.int8.onnx" to 10_451_715L sha "ff29f39f9fbe637f72535160e9d006d61d872fdab0fce838672265b9b38cf946",
            LABELS,
        ),
    ),

    /** CED-small, 22 M parameters: an alternative sorting model for comparisons. */
    CED_SMALL(
        displayName = "CED-small",
        files = huggingFace(
            repository = "k2-fsa/sherpa-onnx-ced-small-audio-tagging-2024-04-19",
            commit = "afbf166bdf847d68f2adfe0c358be20d704c2289",
            "model.int8.onnx" to 22_626_947L sha "ccb9f661014c047e5878a140eda1d816b5579a9ac32a196f062240872e32f489",
            LABELS,
        ),
    ),
    ;

    override val id: String = name.lowercase().replace('_', '-')

    override val bytes: Long = files.sumOf { it.bytes }

    companion object {
        /** The model that listens through the night. */
        val LISTENER = CED_TINY

        /** The models a sleep is sorted with by default: the setup offers them with the speech model. */
        val RECOMMENDED = listOf(CED_TINY, CED_MINI)

        /** The models that can sort candidates, as the developer settings offer them. */
        val SORTERS = listOf(CED_TINY, CED_MINI, CED_SMALL)

        /** The model with [id]; null if there is none. */
        fun byId(id: String): SoundModel? = entries.find { it.id == id }
    }
}

/** One file of a model: where it is downloaded from, its size in bytes and its SHA-256 (hex). */
data class ModelFile(val name: String, val url: String, val bytes: Long, val sha256: String)

internal data class Checked(val name: String, val bytes: Long, val sha256: String)

internal infix fun Pair<String, Long>.sha(sha256: String) = Checked(first, second, sha256)

internal fun huggingFace(repository: String, commit: String, vararg files: Checked) = files.map {
    ModelFile(it.name, "https://huggingface.co/$repository/resolve/$commit/${it.name}", it.bytes, it.sha256)
}

/** The class names of every CED release, the same file in each. */
private val LABELS = "class_labels_indices.csv" to 14_675L sha "cdd1049833c4b86127c2773ac0d14a2754b6a6d0d1798002ed5c66e699708429"
