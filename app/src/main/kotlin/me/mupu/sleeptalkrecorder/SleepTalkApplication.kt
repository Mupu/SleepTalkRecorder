package me.mupu.sleeptalkrecorder

import android.app.Application
import android.content.Context
import android.util.Log
import me.mupu.sleeptalkrecorder.data.SleepDatabase
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.models.HttpByteSource
import me.mupu.sleeptalkrecorder.models.ModelInstaller
import me.mupu.sleeptalkrecorder.models.ModelLibrary
import me.mupu.sleeptalkrecorder.models.WorkManagerDownloads
import me.mupu.sleeptalkrecorder.readiness.PhoneReadiness
import me.mupu.sleeptalkrecorder.recording.Recorder
import me.mupu.sleeptalkrecorder.retention.AutoDeletion
import me.mupu.sleeptalkrecorder.settings.AppLanguages
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.transcription.EventAnalysis
import me.mupu.sleeptalkrecorder.transcription.SherpaTranscriber
import me.mupu.sleeptalkrecorder.transcription.SpeechRecognition
import me.mupu.sleeptalkrecorder.transcription.TranscriptionRunner
import me.mupu.sleeptalkrecorder.transcription.TranscriptionScheduler
import me.mupu.sleeptalkrecorder.transcription.Transcriptions
import me.mupu.sleeptalkrecorder.transcription.batteryState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /** Sleeps and recordings; audio lives in `noBackupFilesDir`, which backups never include. */
    val sleepRepository = SleepRepository(
        database = SleepDatabase.open(context),
        recordingsDirectory = File(context.noBackupFilesDir, "recordings"),
    )

    /** The user's settings. */
    val settings = SettingsStore.open(context)

    /** Starts and stops recordings and publishes their state. */
    val recorder = Recorder(context)

    /** Downloads and keeps the transcription models in `noBackupFilesDir`, like the recordings. */
    val modelInstaller = ModelInstaller(File(context.noBackupFilesDir, "models"), HttpByteSource)

    /** The transcription models, their downloads and the active one. */
    val modelLibrary = ModelLibrary(modelInstaller, WorkManagerDownloads(context), settings)

    /** Starts speech recognition as the settings say, and for a sleep on request. */
    val transcriptionScheduler = TranscriptionScheduler(context)

    /** Runs speech recognition over the events of sleeps; jobs of [transcriptionScheduler] call it. */
    val transcriptionRunner = TranscriptionRunner(sleepRepository, battery = { batteryState(context) })

    /** Speech recognition as the screens see it. */
    val transcriptions = Transcriptions(transcriptionRunner, transcriptionScheduler, settings, modelLibrary)

    private val appContext = context.applicationContext

    /** Whether the phone's permissions and battery settings let a whole night be recorded. */
    val readiness = PhoneReadiness(context)

    /**
     * Finishes recordings a previous process left running. A new recording waits for it, so it
     * can never finish the recording that just started.
     */
    val interruptedRecordingsFinished: Job = scope.launch { sleepRepository.finishInterruptedRecordings() }

    /** One search for events at a time, so a sleep is never searched twice at once. */
    private val eventSearch = Mutex()

    init {
        scope.launch { AutoDeletion.followSettings(context, settings) }
        scope.launch { settings.flow(Settings.transcriptionMode).collect(transcriptionScheduler::follow) }
        findMissingEvents()
    }

    /** Lets waiting events be recognised as the settings say, for example once a model is installed. */
    fun scheduleTranscription() {
        scope.launch { transcriptionScheduler.follow(settings.flow(Settings.transcriptionMode).first()) }
    }

    /**
     * The phone's speech recognition with the active model, loaded; null if no model is
     * installed or its files cannot be loaded.
     */
    suspend fun openSpeechRecognition(): EventAnalysis? {
        val model = settings.flow(Settings.transcriptionModel).first()
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
