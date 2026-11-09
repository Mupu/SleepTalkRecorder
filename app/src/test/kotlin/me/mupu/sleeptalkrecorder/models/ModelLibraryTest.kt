package me.mupu.sleeptalkrecorder.models

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import me.mupu.sleeptalkrecorder.models.TranscriptionModel.PARAKEET_ULTRA
import me.mupu.sleeptalkrecorder.models.TranscriptionModel.WHISPER_SMALL
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLibraryTest {

    private class InMemoryDataStore : DataStore<Preferences> {
        override val data = MutableStateFlow(preferencesOf())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private class FakeDownloads : ModelDownloads {
        override val states = MutableStateFlow(emptyMap<TranscriptionModel, ModelState>())
        val cancelled = mutableListOf<TranscriptionModel>()

        override fun start(model: TranscriptionModel) {
            states.value += model to ModelState.Waiting
        }

        override fun cancel(model: TranscriptionModel) {
            cancelled += model
        }
    }

    private val directory: File = Files.createTempDirectory("models").toFile()
    private val settings = SettingsStore(InMemoryDataStore())
    private val downloads = FakeDownloads()
    private val library = ModelLibrary(ModelInstaller(directory, { _, _ -> error("no network") }), downloads, settings)

    @After
    fun deleteDirectory() {
        directory.deleteRecursively()
    }

    /** Puts the model's files in place, as a finished install leaves them. */
    private fun install(model: TranscriptionModel) {
        File(directory, model.id).mkdirs()
        model.files.forEach { File(directory, "${model.id}/${it.name}").writeText("checked") }
    }

    private suspend fun states() = library.models.first().associate { it.model to it.state }

    private suspend fun active() = library.models.first().singleOrNull { it.active }?.model

    @Test
    fun `lists installed, downloading and missing models, and only an installed one is active`() = runTest {
        assertEquals(null, active())

        install(WHISPER_SMALL)
        library.install(PARAKEET_ULTRA)

        assertEquals(mapOf(PARAKEET_ULTRA to ModelState.Waiting, WHISPER_SMALL to ModelState.Installed), states())
        assertEquals(null, active())
    }

    @Test
    fun `the first installed model becomes active, a later one only when chosen`() = runTest {
        install(WHISPER_SMALL)
        library.installed(WHISPER_SMALL)
        assertEquals(WHISPER_SMALL, active())

        install(PARAKEET_ULTRA)
        library.installed(PARAKEET_ULTRA)
        assertEquals(WHISPER_SMALL, active())

        library.activate(PARAKEET_ULTRA)
        assertEquals(PARAKEET_ULTRA, active())
    }

    @Test
    fun `a model that is not installed cannot be activated`() = runTest {
        install(PARAKEET_ULTRA)

        library.activate(WHISPER_SMALL)

        assertEquals(PARAKEET_ULTRA, settings.flow(Settings.transcriptionModel).first())
    }

    @Test
    fun `deleting the active model removes its files and hands over to another installed one`() = runTest {
        install(PARAKEET_ULTRA)
        install(WHISPER_SMALL)

        library.delete(PARAKEET_ULTRA)

        assertEquals(mapOf(PARAKEET_ULTRA to ModelState.NotInstalled, WHISPER_SMALL to ModelState.Installed), states())
        assertEquals(WHISPER_SMALL, active())
        assertEquals(listOf(PARAKEET_ULTRA), downloads.cancelled)
    }
}
