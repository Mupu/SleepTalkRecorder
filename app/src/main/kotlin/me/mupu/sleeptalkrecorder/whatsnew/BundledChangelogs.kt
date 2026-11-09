package me.mupu.sleeptalkrecorder.whatsnew

import android.content.Context
import me.mupu.sleeptalkrecorder.settings.AppLanguage
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * The changelogs the build bundles into the app's assets, one per app language: `CHANGELOG.md` in
 * English and `CHANGELOG.<tag>.md` for every translation, copied from the repository root.
 *
 * [read] gives a bundled file's text by name, or null if it is missing or cannot be read. A language
 * whose changelog is missing or has no release shows the English one, like any untranslated text.
 */
class BundledChangelogs(private val read: (fileName: String) -> String?) {

    private val parsed = ConcurrentHashMap<AppLanguage, List<Release>>()

    /** The releases in [language], newest first; read once and kept, so call it off the main thread first. */
    fun releases(language: AppLanguage): List<Release> = parsed.getOrPut(language) {
        val own = read(fileName(language))?.let(Changelog::parse).orEmpty()
        if (own.isEmpty() && language != AppLanguage.FALLBACK) releases(AppLanguage.FALLBACK) else own
    }

    companion object {
        /** The folder of the changelogs in the app's assets; the build fills it. */
        const val ASSET_FOLDER = "changelogs"

        /** The changelogs in the assets of [context]'s app. */
        fun fromAssets(context: Context): BundledChangelogs {
            val assets = context.applicationContext.assets
            return BundledChangelogs { name ->
                try {
                    assets.open("$ASSET_FOLDER/$name").bufferedReader().use { it.readText() }
                } catch (_: IOException) {
                    null
                }
            }
        }

        private fun fileName(language: AppLanguage) =
            if (language == AppLanguage.FALLBACK) "CHANGELOG.md" else "CHANGELOG.${language.tag}.md"
    }
}
