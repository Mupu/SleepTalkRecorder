package me.mupu.sleeptalkrecorder.recording

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import me.mupu.sleeptalkrecorder.SleepTalkApplication
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.time.latestStop
import me.mupu.sleeptalkrecorder.ui.format.ClockFormatter
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The foreground service of type `microphone` that records a sleep with the screen off.
 *
 * Android grants a microphone service access only when it is started while the app is visible or
 * from a notification; a service restarted by the system later would get silence, so it is not
 * sticky. A partial wake lock covers the write path in case a vendor ROM does not keep the CPU
 * awake for audio capture on its own.
 */
class RecordingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: Job? = null

    private val container by lazy { (application as SleepTalkApplication).container }
    private val notifications by lazy { RecordingNotifications(this) }
    private val wakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepTalkRecorder:Recording")
            .apply { setReferenceCounted(false) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stop()
            else -> if (session == null) stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    private fun start() {
        if (session != null) return

        notifications.createChannels()
        notifications.cancelStoppedAtLatest()
        try {
            startForeground(
                RecordingNotifications.NOTIFICATION_ID,
                notifications.recording(startedAt = null),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } catch (_: SecurityException) {
            // The microphone permission is missing, or the app was not visible.
            finish(RecorderState.Failed(RecorderProblem.NOT_ALLOWED))
            return
        } catch (_: ForegroundServiceStartNotAllowedException) {
            finish(RecorderState.Failed(RecorderProblem.NOT_ALLOWED))
            return
        }
        wakeLock.acquire(WAKE_LOCK_LIMIT.toMillis())
        container.recorder.report(RecorderState.Recording(progress = null))

        session = scope.launch {
            container.interruptedRecordingsFinished.join()

            val (end, stopTime) = coroutineScope {
                // Follows the setting, so a stop time changed during the recording applies to it.
                val stopAt = latestStopTimes().stateIn(this)
                val end = withContext(Dispatchers.IO) {
                    var shownStart: Instant? = null
                    RecordingSession(
                        source = MicrophoneSource(this@RecordingService),
                        store = container.sleepRepository,
                        recordingsDirectory = container.sleepRepository.recordingsDirectory,
                        clocks = SystemClocks,
                        stopAt = { stopAt.value },
                    ).record { progress ->
                        container.recorder.report(RecorderState.Recording(progress))
                        if (progress.startedAt != shownStart) {
                            shownStart = progress.startedAt
                            notifications.update(notifications.recording(progress.startedAt))
                        }
                    }
                }
                coroutineContext.cancelChildren()
                end to stopAt.value
            }

            // Only reached when the recording ended on its own.
            when (end) {
                RecordingEnd.STOP_TIME_REACHED -> {
                    // Before finishing, which ends the service and this coroutine with it.
                    val clock = ClockFormatter(container.settings.flow(Settings.timeFormat).first())
                    stopTime?.let { notifications.showStoppedAtLatest(it, clock) }
                    finish(RecorderState.Idle)
                }
                RecordingEnd.MICROPHONE_UNAVAILABLE -> finish(RecorderState.Failed(RecorderProblem.MICROPHONE_UNAVAILABLE))
                RecordingEnd.STORAGE_FAILED -> finish(RecorderState.Failed(RecorderProblem.STORAGE_FAILED))
            }
        }
    }

    /**
     * When a recording stops at the latest, or null while the setting is off. Each value counts
     * from the moment it is read: the start of the recording, or a change of the setting.
     */
    private fun latestStopTimes(): Flow<Instant?> = combine(
        container.settings.flow(Settings.stopAtEnabled),
        container.settings.flow(Settings.stopAt),
    ) { enabled, time ->
        if (enabled) latestStop(after = SystemClocks.now(), at = time, zone = ZoneId.systemDefault()) else null
    }

    private fun stop() {
        val running = session
        if (running == null) {
            finish(RecorderState.Idle)
            return
        }
        scope.launch {
            running.cancelAndJoin()
            finish(RecorderState.Idle)
        }
    }

    private fun finish(state: RecorderState) {
        session = null
        container.recorder.report(state)
        // Events are found right after the stop, and after a recording that failed.
        container.findMissingEvents()
        if (wakeLock.isHeld) wakeLock.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        private const val ACTION_START = "me.mupu.sleeptalkrecorder.action.START_RECORDING"
        private const val ACTION_STOP = "me.mupu.sleeptalkrecorder.action.STOP_RECORDING"

        /** Longer than any sleep; only a safety net should the service never be stopped. */
        private val WAKE_LOCK_LIMIT: Duration = Duration.ofHours(24)

        internal fun startIntent(context: Context) =
            Intent(context, RecordingService::class.java).setAction(ACTION_START)

        internal fun stopIntent(context: Context) =
            Intent(context, RecordingService::class.java).setAction(ACTION_STOP)
    }
}
