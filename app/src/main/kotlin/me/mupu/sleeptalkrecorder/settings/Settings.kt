package me.mupu.sleeptalkrecorder.settings

import java.time.Duration

/**
 * Every setting the app stores in its [SettingsStore]. A new setting is one more declaration here;
 * the key must never change once a release has stored it.
 *
 * The app language is not here: Android stores the per-app language itself (see [AppLanguages]),
 * so the system's app settings and this app always agree on it.
 */
object Settings {

    /** Light or dark appearance, or the one the phone uses. */
    val theme = enumSetting("theme", ThemeMode.SYSTEM)

    /** Whether clock times are shown with 24 or 12 hours, whatever the phone uses. */
    val timeFormat = enumSetting("time_format", TimeFormat.HOURS_24)

    /** Whether the first-start question about the app language was answered. */
    val languageChosen = booleanSetting("language_chosen", false)

    /** How much of the sound before an event cut playback plays. */
    val leadIn = durationSetting("lead_in_ms", Duration.ofMillis(1500), Duration.ZERO..Duration.ofSeconds(3))

    /** How much of the sound after an event cut playback plays. */
    val tail = durationSetting("tail_ms", Duration.ofMillis(500), Duration.ZERO..Duration.ofSeconds(3))
}

/** The app's appearance. */
enum class ThemeMode {
    /** Light or dark, as the phone is set. */
    SYSTEM,
    LIGHT,
    DARK,
}

/** How clock times are written. */
enum class TimeFormat {
    /** 00:00 to 23:59. */
    HOURS_24,

    /** 12:00 AM to 11:59 PM. */
    HOURS_12,
}
