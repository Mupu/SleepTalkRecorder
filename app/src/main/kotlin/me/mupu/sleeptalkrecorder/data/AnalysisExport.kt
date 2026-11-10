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
    /** No export runs; [bytes] is the size of the one on storage, null without one. */
    data class Idle(val bytes: Long?) : ExportState

    /** Copying everything; it replaces an earlier export. */
    data object Running : ExportState

    /** The last export failed: the shared storage is missing or too small, or a copy failed. */
    data object Failed : ExportState
}

/**
 * Copies everything analysis needs onto the app's own folder of shared storage, [target], where a
 * PC reads it over USB with adb: the database with every sleep, recording and annotation as
 * `sleeps.db`, and every recording's directory with its chunk WAVs and feature track under
 * `recordings/`. The private storage itself cannot be read from outside in a release build.
 *
 * A new export replaces the old one; it takes as much space as the recordings. It runs in [scope],
 * which outlives the screens, so leaving the settings does not stop it.
 */
class AnalysisExport(
    private val database: SleepDatabase,
    private val recordingsDirectory: File,
    private val target: File?,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle(sizeOf(target)))

    /** Where the export stands. */
    val state: StateFlow<ExportState> = mutableState.asStateFlow()

    /** The folder the export goes to, as a path on the phone; null without shared storage. */
    val path: String? get() = target?.absolutePath

    /** Exports everything, replacing an earlier export; does nothing while one runs. */
    fun export() {
        if (mutableState.value == ExportState.Running) return
        mutableState.value = ExportState.Running
        scope.launch {
            mutableState.value = withContext(Dispatchers.IO) {
                try {
                    copyAll()
                    ExportState.Idle(sizeOf(target))
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
        if (mutableState.value == ExportState.Running) return
        scope.launch {
            mutableState.value = withContext(Dispatchers.IO) {
                target?.deleteRecursively()
                ExportState.Idle(null)
            }
        }
    }

    private fun copyAll() {
        val folder = target ?: throw IOException("No shared storage")
        folder.deleteRecursively()
        if (!folder.mkdirs()) throw IOException("Cannot create $folder")

        val needed = sizeOf(recordingsDirectory) ?: 0
        if (folder.usableSpace < needed + SPARE_BYTES) throw IOException("Not enough space for $needed bytes")

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
        if (recordingsDirectory.isDirectory) {
            recordingsDirectory.copyRecursively(File(folder, "recordings"), overwrite = true)
        }
    }

    private companion object {
        const val TAG = "AnalysisExport"

        /** Space left over after an export, so it never fills the phone completely. */
        const val SPARE_BYTES = 200L * 1024 * 1024

        fun sizeOf(folder: File?): Long? =
            folder?.takeIf { it.isDirectory }?.walkBottomUp()?.filter { it.isFile }?.sumOf { it.length() }
    }
}
