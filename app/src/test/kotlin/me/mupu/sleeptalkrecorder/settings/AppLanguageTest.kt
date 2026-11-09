package me.mupu.sleeptalkrecorder.settings

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {

    private fun matching(vararg tags: String) = AppLanguage.matching(tags.map(Locale::forLanguageTag))

    @Test
    fun `the first of the phone's languages the app speaks is chosen, whatever its region`() {
        assertEquals(AppLanguage.GERMAN, matching("de-AT"))
        assertEquals(AppLanguage.ENGLISH, matching("en-US", "de-DE"))
        assertEquals(AppLanguage.GERMAN, matching("fr-FR", "de-CH", "en-GB"))
    }

    @Test
    fun `a phone in languages the app does not speak gets English`() {
        assertEquals(AppLanguage.ENGLISH, matching("fr-FR", "tr-TR"))
        assertEquals(AppLanguage.ENGLISH, matching())
    }
}
