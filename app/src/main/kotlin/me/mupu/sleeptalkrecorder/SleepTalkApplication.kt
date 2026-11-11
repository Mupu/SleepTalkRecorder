package me.mupu.sleeptalkrecorder

import android.app.Application
import android.content.Context
import android.util.Log
import me.mupu.sleeptalkrecorder.data.AnalysisExport
import me.mupu.sleeptalkrecorder.data.RecordingFiles
import me.mupu.sleeptalkrecorder.data.SleepDatabase
import me.mupu.sleeptalkrecorder.data.SleepRecordings
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.data.SleepTranscripts
import me.mupu.sleeptalkrecorder.models.HttpByteSource
import me.mupu.sleeptalkrecorder.models.ModelInstaller
import me.mupu.sleeptalkrecorder.models.ModelLibrary
import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import me.mupu.sleeptalkrecorder.models.WorkManagerDownloads
import me.mupu.sleeptalkrecorder.readiness.PhoneReadiness
import me.mupu.sleeptalkrecorder.recording.Recorder
import me.mupu.sleeptalkrecorder.recording.RecorderState
import me.mupu.sleeptalkrecorder.retention.AutoDeletion
import me.mupu.sleeptalkrecorder.settings.AppLanguages
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.share.EventShares
import me.mupu.sleeptalkrecorder.transcription.EventAnalysis
import me.mupu.sleeptalkrecorder.transcription.SherpaTranscriber
import me.mupu.sleeptalkrecorder.transcription.SpeechRecognition
import me.mupu.sleeptalkrecorder.transcription.TranscriptionPauses
import me.mupu.sleeptalkrecorder.transcription.TranscriptionRunner
import me.mupu.sleeptalkrecorder.transcription.TranscriptionScheduler
import me.mupu.sleeptalkrecorder.transcription.Transcriptions
import me.mupu.sleeptalkrecorder.transcription.batteryState
import me.mupu.sleeptalkrecorder.transcription.isPluggedIn
import me.mupu.sleeptalkrecorder.ui.setup.SetupStep
import me.mupu.sleeptalkrecorder.update.AppActivity
import me.mupu.sleeptalkrecorder.update.GitHubReleases
import me.mupu.sleeptalkrecorder.update.PackageApks
import me.mupu.sleeptalkrecorder.update.SessionInstaller
import me.mupu.sleeptalkrecorder.update.Updater
import me.mupu.sleeptalkrecorder.update.isMetered
import me.mupu.sleeptalkrecorder.whatsnew.BundledChangelogs
import me.mupu.sleeptalkrecorder.whatsnew.Version
import me.mupu.sleeptalkrecorder.whatsnew.WhatsNew
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The application; it owns the [AppContainer] that screens and the recording service share. */
class SleepTalkApplication : Application() {

    /** The app's shared objects; created before any activity or service. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** The objects that live as long as the process, wired by hand. */
class AppContainer(context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val database = SleepDatabase.open(context)

    /** The recordings' audio, in `noBackupFilesDir`, which backups never include. */
    val recordingFiles = RecordingFiles(File(context.noBackupFilesDir, "recordings"))

    /** Copies every sleep onto the app's folder of shared storage, where a PC reads it with adb. */
    val analysisExport = AnalysisExport(
        database,
        recordingFiles.recordingsDirectory,
        context.getExternalFilesDir(null)?.let { File(it, "export") },
        scope,
    )

    /** The user's settings. */
    val settings = SettingsStore.open(context)

    /** Sleeps, their recordings and their events, as the screens read and change them. */
    val sleepRepository = SleepRepository(database, recordingFiles, settings.flow(Settings.transcriptionModel).map { it.id })

    /** The version of the installed app; the build takes it from the newest release of the changelog. */
    val installedVersion: Version = checkNotNull(Version.parse(BuildConfig.VERSION_NAME))

    /** The changelogs bundled into the app, one per app language. */
    val changelogs = BundledChangelogs.fromAssets(context)

    /** Whether an update brought news the user has not seen. */
    val whatsNew = WhatsNew(settings, installedVersion)

    /** Where the recording service stores its recordings; new sleeps are named by the current windows. */
    val sleepRecordings = SleepRecordings(database, recordingFiles) { settings.flow(Settings.sleepWindows).first() }

    /** Starts and stops recordings and publishes their state. */
    val recorder = Recorder(context)

    /** Downloads and keeps the transcription models in `noBackupFilesDir`, like the recordings. */
    val modelInstaller = ModelInstaller(File(context.noBackupFilesDir, "models"), HttpByteSource)

    /** The transcription models, their downloads and the active one. */
    val modelLibrary = ModelLibrary(modelInstaller, WorkManagerDownloads(context), settings)

    /** Starts speech recognition as the settings say, and for a sleep on request. */
    val transcriptionScheduler = TranscriptionScheduler(context)

    /** The sleeps whose speech recognition the user paused. */
    val transcriptionPauses = TranscriptionPauses(settings)

    /** Runs speech recognition over the events of sleeps; jobs of [transcriptionScheduler] call it. */
    val transcriptionRunner = TranscriptionRunner(
        SleepTranscripts(database, recordingFiles),
        transcriptionPauses,
        battery = { batteryState(context) },
        onFailure = { message, error -> Log.w("Transcription", message, error) },
    )

    /** Speech recognition as the screens see it. */
    val transcriptions = Transcriptions(
        transcriptionRunner,
        transcriptionScheduler,
        transcriptionPauses,
        settings,
        modelLibrary,
        scope,
        pluggedIn = { isPluggedIn(context) },
    ) { openSpeechRecognition(it) }

    private val appContext = context.applicationContext

    /** Writes events as audio files for Android's share sheet. */
    val eventShares = EventShares(appContext)

    /** Whether the phone's permissions and battery settings let a whole night be recorded. */
    val readiness = PhoneReadiness(context)

    private val visible = MutableStateFlow(false)

    /** Whether the app is on the screen; `MainActivity` reports it. */
    val appVisible: StateFlow<Boolean> = visible.asStateFlow()

    private val playing = MutableStateFlow(false)

    /** Tells whether a sleep's audio plays; the player of the sleep screen reports it. */
    fun playbackChanged(isPlaying: Boolean) {
        playing.value = isPlaying
    }

    /** Tells whether the app is on the screen. */
    fun visibilityChanged(isVisible: Boolean) {
        visible.value = isVisible
    }

    private val activity: StateFlow<AppActivity> = combine(
        recorder.state,
        playing,
        transcriptionRunner.progress,
        visible,
    ) { recording, isPlaying, transcription, isVisible ->
        AppActivity(
            recording = recording is RecorderState.Recording || recording == RecorderState.Starting,
            playing = isPlaying,
            transcribing = transcription != null,
            visible = isVisible,
        )
    }.stateIn(scope, SharingStarted.Eagerly, AppActivity(recording = false, playing = false, transcribing = false, visible = false))

    /** Keeps the app up to date from its releases; off in builds without an update source. */
    val updater: Updater = Updater(
        installedVersion = installedVersion,
        source = BuildConfig.UPDATE_SOURCE.takeIf { it.isNotEmpty() }
            ?.let { GitHubReleases(it, "SleepTalkRecorder/${BuildConfig.VERSION_NAME}") },
        files = HttpByteSource,
        // Downloads go to the cache, which the system may clear; then the next start downloads again.
        directory = File(context.cacheDir, "updates"),
        apks = PackageApks(appContext),
        installer = SessionInstaller(appContext) { updater.installFinished(it) },
        metered = { isMetered(appContext) },
        activity = activity,
        scope = scope,
    )

    /** The pages of the first-start setup in this build: the one about updates only where the app updates itself. */
    val setupSteps: List<SetupStep> =
        SetupStep.entries.filter { it != SetupStep.UPDATES || BuildConfig.UPDATE_SOURCE.isNotEmpty() }

    private var startedBefore = false

    /**
     * The app's screen was created: opened anew, or recreated (on a rotation, or after Android
     * ended the process in the background). Each start of the app looks for an update, unless the
     * user switched that off; a recreation within the same process is no start.
     */
    fun appStarted(openedAnew: Boolean) {
        val start = openedAnew || !startedBefore
        startedBefore = true
        if (!start) return
        scope.launch {
            if (settings.flow(Settings.checkForUpdates).first()) updater.check()
        }
    }

    /**
     * Finishes recordings a previous process left running. A new recording waits for it, so it
     * can never finish the recording that just started.
     */
    val interruptedRecordingsFinished: Job = scope.launch { sleepRecordings.finishInterruptedRecordings() }

    /** One search for events at a time, so a sleep is never searched twice at once. */
    private val eventSearch = Mutex()

    init {
        scope.launch { AutoDeletion.followSettings(context, settings) }
        scope.launch { settings.flow(Settings.transcriptionMode).collect(transcriptions::follow) }
        findMissingEvents()
    }

    /** Lets waiting events be recognised as the settings say, for example once a model is installed. */
    fun scheduleTranscription() {
        scope.launch { transcriptionScheduler.follow(settings.flow(Settings.transcriptionMode).first()) }
    }

    /** Pauses speech recognition of [sleepId], from the notification, where nothing else waits for it. */
    fun pauseTranscription(sleepId: Long): Job = scope.launch { transcriptions.pause(sleepId) }

    /** A recording ended: its events are found, and pauses that end with a recording end. */
    fun recordingEnded() {
        scope.launch { transcriptions.recordingEnded() }
        findMissingEvents()
    }

    /**
     * The phone's speech recognition with the [chosen] model or else the active one, loaded; null
     * if the model is not installed or its files cannot be loaded.
     */
    suspend fun openSpeechRecognition(chosen: TranscriptionModel? = null): EventAnalysis? {
        val model = chosen ?: settings.flow(Settings.transcriptionModel).first()
        val language = settings.flow(Settings.transcriptLanguage).first().code ?: AppLanguages.current(appContext).tag
        return withContext(Dispatchers.IO) {
            if (!modelInstaller.isInstalled(model.id, model.files)) return@withContext null
            try {
                SpeechRecognition(model, SherpaTranscriber.load(model, modelInstaller.modelDirectory(model.id), language))
            } catch (error: IllegalArgumentException) {
                Log.e("Transcription", "Cannot load ${model.id}", error)
                null
            }
        }
    }

    /**
     * Finds the events of every finished sleep that has none yet, in the background: at process
     * start (for recordings a killed process left behind) and after every recording ends.
     */
    fun findMissingEvents() {
        scope.launch {
            interruptedRecordingsFinished.join()
            eventSearch.withLock { sleepRepository.findMissingEvents() }
            transcriptionScheduler.follow(settings.flow(Settings.transcriptionMode).first())
        }
    }
}
