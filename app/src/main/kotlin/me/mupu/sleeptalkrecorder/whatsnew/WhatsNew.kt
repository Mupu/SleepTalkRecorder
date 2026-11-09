package me.mupu.sleeptalkrecorder.whatsnew

import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Whether the user has news to see after an update: the releases after the version they last saw
 * up to the [installed] one, kept in [Settings.whatsNewSeen].
 *
 * A fresh install has nothing to announce, the whole changelog would be news, so the first open
 * only stores the installed version. A downgrade stores the lower version, so the next update
 * announces everything after it again.
 */
class WhatsNew(private val settings: SettingsStore, private val installed: Version) {

    /**
     * The version after which the releases are news to the user, or null when there are none.
     * It stays the same until [markSeen], so news not yet looked at are shown on the next open again.
     */
    suspend fun unseenSince(): Version? {
        val seen = settings.flow(Settings.whatsNewSeen).first()
        if (seen != null && seen < installed) return seen
        if (seen != installed) settings.set(Settings.whatsNewSeen, installed)
        return null
    }

    /** Records that the user has seen the news up to the installed version. */
    suspend fun markSeen() {
        settings.set(Settings.whatsNewSeen, installed)
    }
}
