package me.mupu.sleeptalkrecorder.whatsnew

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.preferencesOf
import me.mupu.sleeptalkrecorder.settings.AppLanguage
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WhatsNewTest {

    private class InMemoryDataStore : DataStore<Preferences> {
        override val data = MutableStateFlow(preferencesOf())

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
            transform(data.value).also { data.value = it }
    }

    private val settings = SettingsStore(InMemoryDataStore())

    @Test
    fun `a fresh install only records its version`() = runTest {
        assertNull(WhatsNew(settings, Version(0, 2, 0)).unseenSince())

        assertEquals(Version(0, 2, 0), settings.flow(Settings.whatsNewSeen).first())
    }

    @Test
    fun `after an update the news since the last seen version wait until they are seen`() = runTest {
        settings.set(Settings.whatsNewSeen, Version(0, 1, 0))
        val whatsNew = WhatsNew(settings, Version(0, 3, 0))

        assertEquals(Version(0, 1, 0), whatsNew.unseenSince())
        assertEquals(Version(0, 1, 0), whatsNew.unseenSince())

        whatsNew.markSeen()
        assertNull(whatsNew.unseenSince())
    }

    @Test
    fun `the same version or a downgrade shows nothing, and a downgrade records its version`() = runTest {
        settings.set(Settings.whatsNewSeen, Version(0, 2, 0))
        assertNull(WhatsNew(settings, Version(0, 2, 0)).unseenSince())

        assertNull(WhatsNew(settings, Version(0, 1, 0)).unseenSince())
        assertEquals(Version(0, 1, 0), settings.flow(Settings.whatsNewSeen).first())
    }

    @Test
    fun `each app language reads its own changelog`() {
        val changelogs = BundledChangelogs(files::get)

        assertEquals(Version(2, 0, 0), changelogs.releases(AppLanguage.GERMAN).single().version)
        assertEquals(Version(1, 0, 0), changelogs.releases(AppLanguage.ENGLISH).single().version)
    }

    @Test
    fun `a language without a readable changelog falls back to English`() {
        val withoutGerman = BundledChangelogs { name -> files[name].takeIf { name == "CHANGELOG.md" } }
        val damagedGerman = BundledChangelogs { name -> if (name == "CHANGELOG.de.md") "no releases" else files[name] }

        assertEquals(Version(1, 0, 0), withoutGerman.releases(AppLanguage.GERMAN).single().version)
        assertEquals(Version(1, 0, 0), damagedGerman.releases(AppLanguage.GERMAN).single().version)
    }

    // Different versions per file only tell the files apart; real changelogs have the same sections.
    private val files = mapOf(
        "CHANGELOG.md" to "## [1.0.0] - 2027-01-01\n\n- English",
        "CHANGELOG.de.md" to "## [2.0.0] - 2027-01-01\n\n- Deutsch",
    )
}
