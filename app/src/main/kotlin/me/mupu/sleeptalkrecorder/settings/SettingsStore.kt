package me.mupu.sleeptalkrecorder.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The user's [Settings], kept in a Preferences DataStore file.
 *
 * Storage problems never reach the caller: a file that cannot be read gives the defaults, a damaged
 * file is replaced by an empty one, and a change that cannot be written is dropped, so the screens
 * keep showing the value that is actually stored.
 */
class SettingsStore(private val dataStore: DataStore<Preferences>) {

    /** The current value of [setting], and every later change of it. */
    fun <T> flow(setting: Setting<T>): Flow<T> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { setting.read(it) }
        .distinctUntilChanged()

    /** Stores [value] for [setting]. */
    suspend fun <T> set(setting: Setting<T>, value: T) {
        try {
            dataStore.edit { setting.write(it, value) }
        } catch (_: IOException) {
            // Nothing to recover: the flow keeps the stored value, which the screen then shows.
        }
    }

    companion object {
        /** The store of the app; open it once per process, as DataStore requires. */
        fun open(context: Context) = SettingsStore(
            PreferenceDataStoreFactory.create(
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile("settings") },
            ),
        )
    }
}
