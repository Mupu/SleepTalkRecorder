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
 *
 * A build may leave steps out (debug builds without an update source skip [UPDATES]); [current]
 * and [back] take the steps it shows.
 */
enum class SetupStep(private val finished: Setting<Boolean>) {
    /** Which language the app speaks. */
    LANGUAGE(Settings.languageChosen),

    /** Microphone, notifications, battery optimisation and the vendor's own app killers. */
    PERMISSIONS(Settings.permissionsReviewed),

    /** "Install unknown apps", which lets the app install its updates without asking each time. */
    UPDATES(Settings.updatesReviewed),

    /** The speech recognition model, downloaded in the background or left for later. */
    MODEL(Settings.modelOffered),
    ;

    /** Marks this step as done, which shows the next one. */
    suspend fun finish(store: SettingsStore) = store.set(finished, true)

    /** Opens the step before this one of [steps] again; the first step has none. */
    suspend fun back(store: SettingsStore, steps: List<SetupStep> = entries) {
        val previous = steps.getOrNull(steps.indexOf(this) - 1) ?: return
        store.set(previous.finished, false)
    }

    companion object {
        /** The step of [steps] the setup is at, or null once every one is finished. */
        fun current(store: SettingsStore, steps: List<SetupStep> = entries): Flow<SetupStep?> =
            combine(steps.map { step -> store.flow(step.finished) }) { finished ->
                steps.filterIndexed { index, _ -> !finished[index] }.firstOrNull()
            }.distinctUntilChanged()
    }
}
