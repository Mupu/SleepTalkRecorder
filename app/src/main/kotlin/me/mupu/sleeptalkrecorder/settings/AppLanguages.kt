package me.mupu.sleeptalkrecorder.settings

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import java.util.Locale

/**
 * The app language, kept by Android as the per-app language: from Android 13 on by the system,
 * which also offers it in the app's system settings; before that by AppCompat in a file of its own
 * (`autoStoreLocales` in the manifest), applied to AppCompat activities only.
 */
object AppLanguages {

    /** The language the phone itself is set to, as far as the app speaks it. */
    fun phoneLanguage(context: Context): AppLanguage =
        AppLanguage.matching(LocaleManagerCompat.getSystemLocales(context).toList())

    /** The language the app is set to, or else the phone's, outside of an activity. */
    fun current(context: Context): AppLanguage {
        val chosen = AppCompatDelegate.getApplicationLocales()
        return if (chosen.isEmpty) phoneLanguage(context) else AppLanguage.matching(chosen.toList())
    }

    /** The language the app shows in [configuration], which is the activity's. */
    fun shown(configuration: Configuration): AppLanguage =
        AppLanguage.matching(LocaleListCompat.wrap(configuration.locales).toList())

    /** Switches the app to [language]; Android recreates the open activity in it. */
    fun apply(language: AppLanguage) {
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language.tag))
    }

    private fun LocaleListCompat.toList(): List<Locale> = (0 until size()).mapNotNull(::get)
}
