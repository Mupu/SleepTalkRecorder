package me.mupu.sleeptalkrecorder.settings

import java.util.Locale

/**
 * A language the app's texts are translated into.
 *
 * Adding one takes a constant here and its `values-<tag>/strings.xml`; the build lists it for the
 * system's per-app language setting on its own.
 */
enum class AppLanguage(
    /** The BCP 47 language tag, without region: the app speaks one German for all regions. */
    val tag: String,
    /** The language's name in itself, so it can be found whatever language the app shows. */
    val nativeName: String,
) {
    GERMAN("de", "Deutsch"),
    ENGLISH("en", "English"),
    ;

    companion object {
        /** The language of the untranslated texts in `values/`, shown when no other one fits. */
        val FALLBACK = ENGLISH

        /**
         * The language the app shows for [locales], a user's languages in order of preference:
         * the first one the app speaks, or [FALLBACK]. Android picks resources the same way.
         */
        fun matching(locales: List<Locale>): AppLanguage =
            locales.firstNotNullOfOrNull { locale -> entries.find { it.tag == locale.language } } ?: FALLBACK
    }
}
