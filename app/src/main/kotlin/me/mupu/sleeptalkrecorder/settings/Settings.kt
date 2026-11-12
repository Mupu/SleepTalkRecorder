package me.mupu.sleeptalkrecorder.settings

import me.mupu.sleeptalkrecorder.data.EventWay
import me.mupu.sleeptalkrecorder.detection.SearchSettings
import me.mupu.sleeptalkrecorder.models.SoundModel
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.share.CaptionItem
import me.mupu.sleeptalkrecorder.time.SleepWindows
import java.time.Duration
import java.time.LocalTime
import kotlinx.coroutines.flow.first

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

    /** Whether the first-start page about installing updates ("Install unknown apps") was finished. */
    val updatesReviewed = booleanSetting("updates_reviewed", false)

    /** Whether the app looks for a newer release each time it starts. */
    val checkForUpdates = booleanSetting("check_for_updates", true)

    /** Whether the first-start page offering the speech recognition model was finished. */
    val modelOffered = booleanSetting("model_offered", false)

    /** The model transcripts are written with; see `ModelLibrary` for when it changes on its own. */
    val transcriptionModel = enumSetting("transcription_model", TranscriptionModel.DEFAULT)

    /** The user's own language, for models that need it; English is always recognised as well. */
    val transcriptLanguage = enumSetting("transcript_language", TranscriptLanguage.APP)

    /** When speech recognition runs over the events of finished sleeps. */
    val transcriptionMode = enumSetting("transcription_mode", TranscriptionMode.AFTER_STOP)

    /** The sleeps whose speech recognition the user paused, as `TranscriptionPauses` writes them. */
    val transcriptionPauses = textSetting("transcription_pauses")

    /** How much of the sound before an event cut playback plays. */
    val leadIn = durationSetting("lead_in_ms", Duration.ofMillis(1000), Duration.ZERO..Duration.ofSeconds(3))

    /** How much of the sound after an event cut playback plays. */
    val tail = durationSetting("tail_ms", Duration.ofMillis(500), Duration.ZERO..Duration.ofSeconds(3))

    /** What the caption of a shared event names; the share step changes it for the next share too. */
    val shareCaption = enumSetSetting("share_caption", CaptionItem.DEFAULT)

    /** Whether a recording stops on its own at [stopAt], in case nobody stops it in the morning. */
    val stopAtEnabled = booleanSetting("stop_at_enabled", true)

    /** The clock time at which a recording stops at the latest while [stopAtEnabled] is on. */
    val stopAt = timeOfDaySetting("stop_at_minute", LocalTime.NOON)

    /** When nights and naps begin; a change names only sleeps that start afterwards. */
    val sleepWindows = sleepWindowsSetting("naps_from_minute", "nights_from_minute", SleepWindows.DEFAULT)

    /**
     * The version whose news the user has seen: after an update to a higher one the app shows what
     * is new since. Null until the app is first opened, which only stores the installed version.
     */
    val whatsNewSeen = versionSetting("whats_new_seen_version")

    /** Whether sleeps older than [autoDeleteAfter] are deleted on their own. */
    val autoDelete = booleanSetting("auto_delete", false)

    /** How long sleeps are kept when [autoDelete] is on; whole days, kept while it is off. */
    val autoDeleteAfter = durationSetting(
        "auto_delete_after_ms",
        Duration.ofDays(30),
        Duration.ofDays(1)..Duration.ofDays(365),
    )

    /** Whether automatic deletion keeps sleeps with an event that has a tag of the user or a name. */
    val keepTagged = booleanSetting("keep_tagged", true)

    /**
     * The tags the tag window gave and took away when its OK was last pressed, as `TagChanges`
     * writes them; the window opens with them applied.
     */
    val tagChanges = textSetting("tag_changes")

    /** Which way's events the sleep screen shows: the first way's by default during the test phase of the second. */
    val eventWay = enumSetting("event_way", EventWay.OLD)

    /**
     * Whether the second way of finding events searches the sleep while it is recorded, under the
     * conditions of speech recognition ([TranscriptionMode]); off by default.
     */
    val nightScan = booleanSetting("night_scan", false)

    /** Whether the settings show the developer settings and the sleep screen what the second way of finding events knows. */
    val developerMode = booleanSetting("developer_mode", false)

    /** The second way's loudness sensitivity ([SearchSettings.sensitivity]); a developer setting, like those below. */
    val searchSensitivity = floatSetting("search_sensitivity", SearchSettings().sensitivity, 0f..1f)

    /** Whether the second way's listening pass runs ([SearchSettings.listening]). */
    val searchListening = booleanSetting("search_listening", SearchSettings().listening)

    /** The listening pass's threshold ([SearchSettings.listeningThreshold]). */
    val listeningThreshold = floatSetting("search_listening_threshold", SearchSettings().listeningThreshold, 0.02f..0.6f)

    /** The human score below which a candidate can be background ([SearchSettings.backgroundThreshold]). */
    val backgroundThreshold = floatSetting("search_background_threshold", SearchSettings().backgroundThreshold, 0f..0.5f)

    /** Whether the onset rule votes on background ([SearchSettings.onsetVote]). */
    val onsetVote = booleanSetting("search_onset_vote", SearchSettings().onsetVote)

    /** The model that sorts the second way's candidates ([SearchSettings.sortingModel]). */
    val sortingModel = enumSetting("search_sorting_model", SoundModel.CED_MINI, SoundModel.SORTERS)
}

/** The second way's settings as they are stored now, which the next search of a sleep uses. */
suspend fun SettingsStore.searchSettings(): SearchSettings = SearchSettings(
    sensitivity = flow(Settings.searchSensitivity).first(),
    listening = flow(Settings.searchListening).first(),
    listeningThreshold = flow(Settings.listeningThreshold).first(),
    backgroundThreshold = flow(Settings.backgroundThreshold).first(),
    onsetVote = flow(Settings.onsetVote).first(),
    sortingModel = flow(Settings.sortingModel).first().id,
)

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

    /** Never: the sleep screen offers no run, and transcripts made before stay as they are. */
    OFF,
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
