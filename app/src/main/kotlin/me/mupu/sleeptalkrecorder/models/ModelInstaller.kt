package me.mupu.sleeptalkrecorder.models

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** A downloaded file did not have its pinned checksum; it was deleted and has to be downloaded again. */
class DamagedDownloadException(val fileName: String) : Exception("Checksum mismatch: $fileName")

/** The phone lacks the [neededBytes] an install still has to write. */
class NotEnoughSpaceException(val neededBytes: Long) : Exception("$neededBytes bytes needed")

/**
 * Downloads, checks and deletes the files of models in [directory], one sub-directory per model.
 *
 * A file is downloaded as `<name>.part` and gets its own name only once its SHA-256 matched, so a
 * file under its own name is always complete and checked. A `.part` file left by an interrupted
 * download is continued where it stopped by the next install.
 */
class ModelInstaller(
    private val directory: File,
    private val source: ByteSource,
    /** The bytes still free on the storage of [directory]. */
    private val freeBytes: (File) -> Long = File::getUsableSpace,
) {

    /** Whether every file of the model [id] is downloaded and checked. */
    fun isInstalled(id: String, files: List<ModelFile>): Boolean =
        files.all { File(modelDirectory(id), it.name).isFile }

    /** The bytes of model [id] already on disk, checked or partial. */
    fun bytesDone(id: String, files: List<ModelFile>): Long = files.sumOf { done(modelDirectory(id), it) }

    /** The model's directory, from which the model is loaded once it [isInstalled]. */
    fun modelDirectory(id: String) = File(directory, id)

    /**
     * Downloads and checks the files of model [id] that are not installed yet, continuing partial
     * files, and reports the bytes done of the model's total to [onProgress] as it goes.
     *
     * @throws IOException when the connection or the storage fails; what was written is kept for
     *   the next attempt.
     * @throws DamagedDownloadException when a file has the wrong checksum.
     * @throws NotEnoughSpaceException before downloading anything, when the files do not fit.
     */
    suspend fun install(id: String, files: List<ModelFile>, onProgress: (done: Long, total: Long) -> Unit) =
        withContext(Dispatchers.IO) {
            val target = modelDirectory(id)
            if (!target.isDirectory && !target.mkdirs()) throw IOException("Cannot create $target")

            val total = files.sumOf { it.bytes }
            val missing = files.sumOf { it.bytes - done(target, it) }
            val needed = missing + SPACE_RESERVE_BYTES
            if (missing > 0 && freeBytes(target) < needed) throw NotEnoughSpaceException(needed)

            for (file in files) {
                val installed = File(target, file.name)
                if (installed.isFile) continue

                val partial = File(target, file.name + PARTIAL_SUFFIX)
                if (partial.length() > file.bytes) partial.delete()
                if (partial.length() < file.bytes) {
                    val others = files.filter { it != file }.sumOf { done(target, it) }
                    download(file, partial) { written -> onProgress(others + written, total) }
                }

                if (sha256(partial) != file.sha256) {
                    partial.delete()
                    throw DamagedDownloadException(file.name)
                }
                if (!partial.renameTo(installed)) throw IOException("Cannot rename $partial")
            }
            onProgress(total, total)
        }

    /** Deletes model [id] with every file, also partial ones. */
    fun delete(id: String) {
        modelDirectory(id).deleteRecursively()
    }

    /** The bytes of [file] already on disk, installed or partial. */
    private fun done(target: File, file: ModelFile): Long {
        if (File(target, file.name).isFile) return file.bytes
        return File(target, file.name + PARTIAL_SUFFIX).length().coerceAtMost(file.bytes)
    }

    /** Appends the rest of [file] to [partial], telling [onBytes] the bytes the partial file holds. */
    private suspend fun download(file: ModelFile, partial: File, onBytes: (Long) -> Unit) {
        source.open(file.url, partial.length()).use { body ->
            // A body from offset 0 is the whole file again, so it replaces what is there.
            FileOutputStream(partial, body.offset > 0).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var written = body.offset
                while (true) {
                    coroutineContext.ensureActive()
                    val read = body.stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    onBytes(written)
                }
            }
        }
        if (partial.length() < file.bytes) throw IOException("Connection ended early: ${file.name}")
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** Space left free beyond the model's files, so the phone and recordings never run out. */
        const val SPACE_RESERVE_BYTES = 200L * 1000 * 1000

        private const val PARTIAL_SUFFIX = ".part"
        private const val BUFFER_BYTES = 64 * 1024
    }
}
