package me.mupu.sleeptalkrecorder.ui.setup

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SetupStepTest {

    private class InMemoryDataStore : DataStore<Preferences> {
        override val data = MutableStateFlow(preferencesOf())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val store = SettingsStore(InMemoryDataStore())

    @Test
    fun `the setup walks through its steps in order and is over when all are finished`() = runTest {
        assertEquals(SetupStep.LANGUAGE, SetupStep.current(store).first())

        SetupStep.LANGUAGE.finish(store)
        assertEquals(SetupStep.PERMISSIONS, SetupStep.current(store).first())

        SetupStep.PERMISSIONS.finish(store)
        assertNull(SetupStep.current(store).first())
    }

    @Test
    fun `going back from a step opens the one before it again`() = runTest {
        SetupStep.LANGUAGE.finish(store)

        SetupStep.PERMISSIONS.back(store)

        assertEquals(SetupStep.LANGUAGE, SetupStep.current(store).first())
    }
}
