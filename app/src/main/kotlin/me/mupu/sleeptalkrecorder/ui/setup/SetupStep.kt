package me.mupu.sleeptalkrecorder.ui.setup

import me.mupu.sleeptalkrecorder.settings.Setting
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * The pages of the first-start setup, in the order they are shown. Each remembers in its
 * [finished] setting that the user went past it, so a setup left halfway continues where it
 * stopped. A new page is one more constant here and one more screen in `MainActivity`.
 */
enum class SetupStep(private val finished: Setting<Boolean>) {
    /** Which language the app speaks. */
    LANGUAGE(Settings.languageChosen),

    /** Microphone, notifications, battery optimisation and the vendor's own app killers. */
    PERMISSIONS(Settings.permissionsReviewed),

    /** The speech recognition model, downloaded in the background or left for later. */
    MODEL(Settings.modelOffered),
    ;

    /** Marks this step as done, which shows the next one. */
    suspend fun finish(store: SettingsStore) = store.set(finished, true)

    /** Opens the step before this one again; the first step has none. */
    suspend fun back(store: SettingsStore) {
        val previous = entries.getOrNull(ordinal - 1) ?: return
        store.set(previous.finished, false)
    }

    companion object {
        /** The step the setup is at, or null once every step is finished. */
        fun current(store: SettingsStore): Flow<SetupStep?> =
            combine(entries.map { step -> store.flow(step.finished) }) { finished ->
                entries.firstOrNull { !finished[it.ordinal] }
            }.distinctUntilChanged()
    }
}
