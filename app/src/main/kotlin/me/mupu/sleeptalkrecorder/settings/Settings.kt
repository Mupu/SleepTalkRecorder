package me.mupu.sleeptalkrecorder.settings

import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import java.time.Duration
import java.time.LocalTime

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

    /** Whether the first-start page about permissions and battery optimisation was finished. */
    val permissionsReviewed = booleanSetting("permissions_reviewed", false)

    /**
     * Whether the app asked for the microphone permission before; Android shows its dialog only
     * until the user refused twice, after that the app's settings page has to grant it.
     */
    val microphoneAsked = booleanSetting("microphone_asked", false)

    /** Whether the app asked for the notification permission before, like [microphoneAsked]. */
    val notificationsAsked = booleanSetting("notifications_asked", false)

    /** Whether the first-start page offering the speech recognition model was finished. */
    val modelOffered = booleanSetting("model_offered", false)

    /** The model transcripts are written with; see `ModelLibrary` for when it changes on its own. */
    val transcriptionModel = enumSetting("transcription_model", TranscriptionModel.DEFAULT)

    /** The user's own language, for models that need it; English is always recognised as well. */
    val transcriptLanguage = enumSetting("transcript_language", TranscriptLanguage.APP)

    /** When speech recognition runs over the events of finished sleeps. */
    val transcriptionMode = enumSetting("transcription_mode", TranscriptionMode.AFTER_STOP)

    /** How much of the sound before an event cut playback plays. */
    val leadIn = durationSetting("lead_in_ms", Duration.ofMillis(1500), Duration.ZERO..Duration.ofSeconds(3))

    /** How much of the sound after an event cut playback plays. */
    val tail = durationSetting("tail_ms", Duration.ofMillis(500), Duration.ZERO..Duration.ofSeconds(3))

    /** Whether a recording stops on its own at [stopAt], in case nobody stops it in the morning. */
    val stopAtEnabled = booleanSetting("stop_at_enabled", true)

    /** The clock time at which a recording stops at the latest while [stopAtEnabled] is on. */
    val stopAt = timeOfDaySetting("stop_at_minute", LocalTime.NOON)

    /** Whether sleeps older than [autoDeleteAfter] are deleted on their own. */
    val autoDelete = booleanSetting("auto_delete", false)

    /** How long sleeps are kept when [autoDelete] is on; whole days, kept while it is off. */
    val autoDeleteAfter = durationSetting(
        "auto_delete_after_ms",
        Duration.ofDays(30),
        Duration.ofDays(1)..Duration.ofDays(365),
    )
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

/** When speech recognition runs over the events of a finished sleep. */
enum class TranscriptionMode {
    /** Right after the stop; it pauses below 30 % battery until the phone charges. */
    AFTER_STOP,

    /** Only while the phone is charging. */
    WHILE_CHARGING,

    /** Only when the user starts it from a sleep's menu. */
    MANUAL,
}

/**
 * The language a transcription model listens for besides English, for models that need one
 * (`TranscriptionModel.needsLanguage`). [code] is its ISO 639-1 code.
 */
enum class TranscriptLanguage(val code: String?, val nativeName: String?) {
    /** The app's language. */
    APP(null, null),
    GERMAN("de", "Deutsch"),
    ENGLISH("en", "English"),
    SPANISH("es", "Español"),
    FRENCH("fr", "Français"),
    ITALIAN("it", "Italiano"),
    DUTCH("nl", "Nederlands"),
    POLISH("pl", "Polski"),
    PORTUGUESE("pt", "Português"),
    TURKISH("tr", "Türkçe"),
    UKRAINIAN("uk", "Українська"),
    RUSSIAN("ru", "Русский"),
}
