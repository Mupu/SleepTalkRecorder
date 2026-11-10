package me.mupu.sleeptalkrecorder.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import me.mupu.sleeptalkrecorder.time.SleepWindows
import java.time.Duration
import java.time.LocalTime
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
        assertEquals(Duration.ofMillis(1000), store.flow(Settings.leadIn).first())
        assertEquals(Duration.ofMillis(500), store.flow(Settings.tail).first())
        assertEquals(false, store.flow(Settings.languageChosen).first())
        assertEquals(true, store.flow(Settings.stopAtEnabled).first())
        assertEquals(LocalTime.NOON, store.flow(Settings.stopAt).first())
    }

    @Test
    fun `a changed setting keeps its new value`() = runTest {
        val store = SettingsStore(InMemoryDataStore())

        store.set(Settings.theme, ThemeMode.DARK)
        store.set(Settings.leadIn, Duration.ofMillis(2300))
        store.set(Settings.languageChosen, true)
        store.set(Settings.stopAt, LocalTime.of(7, 45))

        assertEquals(ThemeMode.DARK, store.flow(Settings.theme).first())
        assertEquals(Duration.ofMillis(2300), store.flow(Settings.leadIn).first())
        assertEquals(true, store.flow(Settings.languageChosen).first())
        assertEquals(LocalTime.of(7, 45), store.flow(Settings.stopAt).first())
    }

    @Test
    fun `a stored value the app no longer accepts reads as the default or the nearest allowed value`() = runTest {
        val stored = preferencesOf(
            stringPreferencesKey("theme") to "SEPIA",
            longPreferencesKey("lead_in_ms") to 60_000L,
            longPreferencesKey("tail_ms") to -200L,
            intPreferencesKey("stop_at_minute") to 24 * 60,
        )
        val store = SettingsStore(InMemoryDataStore(stored))

        assertEquals(ThemeMode.SYSTEM, store.flow(Settings.theme).first())
        assertEquals(Duration.ofSeconds(3), store.flow(Settings.leadIn).first())
        assertEquals(Duration.ZERO, store.flow(Settings.tail).first())
        assertEquals(LocalTime.of(23, 59), store.flow(Settings.stopAt).first())
    }

    @Test
    fun `the sleep windows are stored together and read back`() = runTest {
        val store = SettingsStore(InMemoryDataStore())
        assertEquals(SleepWindows(LocalTime.NOON, LocalTime.of(18, 0)), store.flow(Settings.sleepWindows).first())

        store.set(Settings.sleepWindows, SleepWindows(LocalTime.of(13, 15), LocalTime.of(21, 59)))

        assertEquals(SleepWindows(LocalTime.of(13, 15), LocalTime.of(21, 59)), store.flow(Settings.sleepWindows).first())
    }

    @Test
    fun `stored sleep windows whose naps start after nights read as the default`() = runTest {
        val stored = preferencesOf(
            intPreferencesKey("naps_from_minute") to 20 * 60,
            intPreferencesKey("nights_from_minute") to 19 * 60,
        )
        val store = SettingsStore(InMemoryDataStore(stored))

        assertEquals(SleepWindows.DEFAULT, store.flow(Settings.sleepWindows).first())
    }
}
