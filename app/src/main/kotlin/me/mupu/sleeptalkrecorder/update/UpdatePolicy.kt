package me.mupu.sleeptalkrecorder.update

/** Who asks for a step of an update: the user with a tap, or the app on its own. */
enum class Trigger {
    USER,
    APP,
}

/** What the app is doing that an install would end: installing an app kills all its processes. */
data class AppActivity(
    val recording: Boolean,
    val playing: Boolean,
    val transcribing: Boolean,
    /** Whether the app is on the screen. */
    val visible: Boolean,
)

/** When an update may be downloaded and installed. */
object UpdatePolicy {

    /** The app downloads on its own only on an unmetered network (Wi-Fi); mobile data needs a tap. */
    fun mayDownload(trigger: Trigger, metered: Boolean): Boolean = trigger == Trigger.USER || !metered

    /**
     * An install ends whatever the app does, so it never happens during a recording. A tap installs
     * at once otherwise; the app installs on its own only when it is not on the screen and nothing
     * plays or recognises speech.
     */
    fun mayInstall(trigger: Trigger, activity: AppActivity): Boolean = when {
        activity.recording -> false
        trigger == Trigger.USER -> true
        else -> !activity.visible && !activity.playing && !activity.transcribing
    }
}
