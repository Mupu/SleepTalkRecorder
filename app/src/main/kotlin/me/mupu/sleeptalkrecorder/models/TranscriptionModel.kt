package me.mupu.sleeptalkrecorder.models

/**
 * A speech recognition model the app can download, run with sherpa-onnx and use for transcripts.
 *
 * Each model is a set of single files from the Hugging Face repository the sherpa-onnx project
 * publishes it in, pinned to one commit and checked against the SHA-256 of each file, so a file
 * changed upstream can never be installed by accident. Single files instead of the project's
 * `.tar.bz2` release archives need no decompressor and no room for an archive next to its
 * contents, and a download can resume file by file.
 */
enum class TranscriptionModel(
    override val displayName: String,
    /**
     * Whether transcripts need the user's language: true for models that detect the language
     * only among a few given ones, false for models that handle every language they know at once.
     */
    val needsLanguage: Boolean,
    override val files: List<ModelFile>,
) : DownloadableModel {
    /**
     * Parakeet TDT 0.6B v3 "Ultra", int8: 25 European languages including German and English in
     * one pass, the default.
     */
    PARAKEET_ULTRA(
        displayName = "Parakeet TDT 0.6B Ultra",
        needsLanguage = false,
        files = huggingFace(
            repository = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8",
            commit = "402e1190ce85b9793118ee223db21fb0d12f52db",
            "encoder.int8.onnx" to 652_282_345L sha "b43072e1b721f18a3df03fcfeb763ff7c4debdf29843598a3aba2f703e2780a3",
            "decoder.int8.onnx" to 11_845_274L sha "1fab98fe6c12aded87d2da66272cc9e148d0a0044ce3850a12fe56302ec4a922",
            "joiner.int8.onnx" to 6_355_277L sha "8a71aaccfdba3d451775507a889afd2257d2e754463fd81095a8c8f290f924c2",
            "tokens.txt" to 93_939L sha "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d",
        ),
    ),

    /** Whisper small, int8: 99 languages, smaller and slower than Parakeet, needs the user's language. */
    WHISPER_SMALL(
        displayName = "Whisper small",
        needsLanguage = true,
        files = huggingFace(
            repository = "csukuangfj/sherpa-onnx-whisper-small",
            commit = "8f3c18b358db4d1f2fc1eae49d75cd20989e4309",
            "small-encoder.int8.onnx" to 112_442_483L sha "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9",
            "small-decoder.int8.onnx" to 262_226_114L sha "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee",
            "small-tokens.txt" to 816_730L sha "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126",
        ),
    ),
    ;

    override val id: String = name.lowercase().replace('_', '-')

    override val bytes: Long = files.sumOf { it.bytes }

    companion object {
        /** The model the setup offers and the app uses unless the user picks another. */
        val DEFAULT = PARAKEET_ULTRA
    }
}
