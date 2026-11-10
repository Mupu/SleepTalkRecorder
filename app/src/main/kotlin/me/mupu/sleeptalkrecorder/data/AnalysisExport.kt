package me.mupu.sleeptalkrecorder.data

import android.database.SQLException
import android.util.Log
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where an export for analysis stands. */
sealed interface ExportState {
    /**
     * No export runs. [exported] is the size of the export on storage, null without one;
     * [recordings] what the recordings take; [toCopy] what a new export would still copy.
     * Both sizes are null until they are counted.
     */
    data class Idle(val exported: Long?, val recordings: Long? = null, val toCopy: Long? = null) : ExportState

    /** Copying what changed since the last export: [copied] of [total] bytes so far. */
    data class Running(val copied: Long = 0, val total: Long = 0) : ExportState

    /** The last export failed: the shared storage is missing or too small, or a copy failed. */
    data object Failed : ExportState
}

/**
 * What an export has to do to make [target] hold the same files as [source]: copy the files that
 * are missing there or differ in size or are newer, and remove those that are gone from
 * [source]. Paths are relative to the two folders, with `/` between their parts.
 */
data class ExportPlan(val copy: List<String>, val remove: List<String>, val bytesToCopy: Long) {

    companion object {
        /** The plan for [source] and [target]; a missing folder counts as empty. */
        fun of(source: File, target: File): ExportPlan {
            val wanted = filesIn(source)
            val present = filesIn(target)
            val copy = wanted.filter { (path, file) ->
                val copied = present[path]
                copied == null || copied.length() != file.length() || file.lastModified() > copied.lastModified()
            }.keys.sorted()
            val remove = (present.keys - wanted.keys).sorted()
            return ExportPlan(copy, remove, copy.sumOf { wanted.getValue(it).length() })
        }

        private fun filesIn(folder: File): Map<String, File> =
            if (!folder.isDirectory) {
                emptyMap()
            } else {
                folder.walkTopDown().filter { it.isFile }.associateBy { it.relativeTo(folder).invariantSeparatorsPath }
            }
    }
}

/**
 * Keeps a copy of everything analysis needs on the app's own folder of shared storage, [target],
 * where a PC reads it over USB with adb: the database with every sleep, recording and annotation
 * as `sleeps.db`, and every recording's directory with its chunk WAVs and feature track under
 * `recordings/`. The private storage itself cannot be read from outside in a release build.
 *
 * A finished recording's files never change, so an export copies only what changed since the
 * last one ([ExportPlan]) and removes the files of sleeps deleted since; the database is copied
 * fresh every time. It runs in [scope], which outlives the screens, so leaving the settings does
 * not stop it.
 */
class AnalysisExport(
    private val database: SleepDatabase,
    private val recordingsDirectory: File,
    private val target: File?,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle(exported = null))

    /** Where the export stands. */
    val state: StateFlow<ExportState> = mutableState.asStateFlow()

    /** The folder the export goes to, as a path on the phone; null without shared storage. */
    val path: String? get() = target?.absolutePath

    private val exportedRecordings: File? get() = target?.let { File(it, RECORDINGS) }

    /** Counts what the recordings take and what an export would copy now; does nothing while one runs. */
    fun refresh() {
        if (mutableState.value is ExportState.Running) return
        scope.launch { mutableState.value = withContext(Dispatchers.IO) { idle() } }
    }

    /** Exports what changed since the last export; does nothing while one runs. */
    fun export() {
        if (mutableState.value is ExportState.Running) return
        mutableState.value = ExportState.Running()
        scope.launch {
            mutableState.value = withContext(Dispatchers.IO) {
                try {
                    sync()
                    idle()
                } catch (e: IOException) {
                    Log.w(TAG, "Export failed", e)
                    ExportState.Failed
                } catch (e: SQLException) {
                    Log.w(TAG, "Export failed", e)
                    ExportState.Failed
                }
            }
        }
    }

    /** Deletes the export to free its space; does nothing while one runs. */
    fun delete() {
        if (mutableState.value is ExportState.Running) return
        scope.launch {
            mutableState.value = withContext(Dispatchers.IO) {
                target?.deleteRecursively()
                idle()
            }
        }
    }

    private fun idle(): ExportState.Idle {
        val copied = exportedRecordings
        return ExportState.Idle(
            exported = sizeOf(target),
            recordings = sizeOf(recordingsDirectory) ?: 0,
            toCopy = copied?.let { ExportPlan.of(recordingsDirectory, it).bytesToCopy },
        )
    }

    private fun sync() {
        val folder = target ?: throw IOException("No shared storage")
        val copied = File(folder, RECORDINGS)
        if (!copied.isDirectory && !copied.mkdirs()) throw IOException("Cannot create $copied")

        val plan = ExportPlan.of(recordingsDirectory, copied)
        if (folder.usableSpace < plan.bytesToCopy + SPARE_BYTES) throw IOException("Not enough space for ${plan.bytesToCopy} bytes")

        for (path in plan.remove) File(copied, path).delete()
        // Directories of deleted recordings are left empty; the deepest go first.
        copied.walkBottomUp().filter { it.isDirectory && it != copied && it.list().isNullOrEmpty() }.forEach { it.delete() }
        var done = 0L
        mutableState.value = ExportState.Running(done, plan.bytesToCopy)
        for (path in plan.copy) {
            val source = File(recordingsDirectory, path)
            val copy = File(copied, path)
            source.copyTo(copy, overwrite = true)
            // The copy takes the source's time, so the next plan sees it as up to date.
            copy.setLastModified(source.lastModified())
            // One step per file: a chunk is at most 15 minutes of audio, so the bar moves steadily.
            done += source.length()
            mutableState.value = ExportState.Running(done, plan.bytesToCopy)
        }

        // A consistent copy of the database while the app keeps using it, the write-ahead log
        // included. SQLite creates it readable by the app alone, which adb cannot pull from shared
        // storage, so it is made in private storage and then copied like the recordings.
        val snapshot = File(recordingsDirectory.parentFile, "export-sleeps.db")
        try {
            snapshot.delete()
            database.openHelper.writableDatabase.execSQL("VACUUM INTO ?", arrayOf(snapshot.absolutePath))
            snapshot.copyTo(File(folder, "sleeps.db"), overwrite = true)
        } finally {
            snapshot.delete()
        }
    }

    private companion object {
        const val TAG = "AnalysisExport"

        /** The export's folder of recordings, beside `sleeps.db`. */
        const val RECORDINGS = "recordings"

        /** Space left over after an export, so it never fills the phone completely. */
        const val SPARE_BYTES = 200L * 1024 * 1024

        fun sizeOf(folder: File?): Long? =
            folder?.takeIf { it.isDirectory }?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() }
    }
}
