package me.mupu.sleeptalkrecorder.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.time.Duration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsStoreTest {

    /** DataStore's file handling is the library's; these tests cover what the app reads and writes. */
    private class InMemoryDataStore(initial: Preferences = preferencesOf()) : DataStore<Preferences> {
        override val data = MutableStateFlow(initial)

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    @Test
    fun `a setting nobody changed has its default`() = runTest {
        val store = SettingsStore(InMemoryDataStore())

        assertEquals(ThemeMode.SYSTEM, store.flow(Settings.theme).first())
        assertEquals(Duration.ofMillis(1500), store.flow(Settings.leadIn).first())
        assertEquals(Duration.ofMillis(500), store.flow(Settings.tail).first())
        assertEquals(false, store.flow(Settings.languageChosen).first())
    }

    @Test
    fun `a changed setting keeps its new value`() = runTest {
        val store = SettingsStore(InMemoryDataStore())

        store.set(Settings.theme, ThemeMode.DARK)
        store.set(Settings.leadIn, Duration.ofMillis(2300))
        store.set(Settings.languageChosen, true)

        assertEquals(ThemeMode.DARK, store.flow(Settings.theme).first())
        assertEquals(Duration.ofMillis(2300), store.flow(Settings.leadIn).first())
        assertEquals(true, store.flow(Settings.languageChosen).first())
    }

    @Test
    fun `a stored value the app no longer accepts reads as the default or the nearest allowed value`() = runTest {
        val stored = preferencesOf(
            stringPreferencesKey("theme") to "SEPIA",
            longPreferencesKey("lead_in_ms") to 60_000L,
            longPreferencesKey("tail_ms") to -200L,
        )
        val store = SettingsStore(InMemoryDataStore(stored))

        assertEquals(ThemeMode.SYSTEM, store.flow(Settings.theme).first())
        assertEquals(Duration.ofSeconds(3), store.flow(Settings.leadIn).first())
        assertEquals(Duration.ZERO, store.flow(Settings.tail).first())
    }
}
