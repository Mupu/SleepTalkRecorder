package me.mupu.sleeptalkrecorder

import android.app.Application
import android.content.Context
import me.mupu.sleeptalkrecorder.data.SleepDatabase
import me.mupu.sleeptalkrecorder.data.SleepRepository
import me.mupu.sleeptalkrecorder.recording.Recorder
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    /** Starts and stops recordings and publishes their state. */
    val recorder = Recorder(context)

    /**
     * Finishes recordings a previous process left running. A new recording waits for it, so it
     * can never finish the recording that just started.
     */
    val interruptedRecordingsFinished: Job = scope.launch { sleepRepository.finishInterruptedRecordings() }
}
