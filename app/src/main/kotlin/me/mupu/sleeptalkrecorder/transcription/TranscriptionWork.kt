package me.mupu.sleeptalkrecorder.transcription

import android.app.Notification
import android.content.Context
import android.os.BatteryManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.SleepTalkApplication
import me.mupu.sleeptalkrecorder.notifications.NotificationIds
import me.mupu.sleeptalkrecorder.notifications.ProgressNotifications
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Runs speech recognition as WorkManager jobs, which survive the app being closed and the process
 * being killed; results are kept per event, so a stopped job continues where it stopped.
 *
 * - [TranscriptionMode.AFTER_STOP]: a job without constraints that pauses itself below 30 %
 *   battery and hands over to a job that waits for the charger.
 * - [TranscriptionMode.WHILE_CHARGING]: only the job that waits for the charger; WorkManager stops
 *   it when the charger is unplugged and runs it again on the next one.
 * - [TranscriptionMode.MANUAL]: no job of its own; [runNow] starts one for a sleep.
 * - [TranscriptionMode.OFF]: no job at all; switching it off cancels every job, also those
 *   started for a sleep.
 */
class TranscriptionScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val pausedForBattery = MutableStateFlow(false)

    /** Whether the last run after the stop paused because the battery was low. */
    val batteryPause: StateFlow<Boolean> = pausedForBattery.asStateFlow()

    /** Makes sure waiting events are recognised as [mode] says, or no longer on their own. */
    fun follow(mode: TranscriptionMode) {
        when (mode) {
            TranscriptionMode.AFTER_STOP -> enqueue(AFTER_STOP, charging = false)
            TranscriptionMode.WHILE_CHARGING -> {
                workManager.cancelUniqueWork(AFTER_STOP)
                enqueue(ON_CHARGER, charging = true)
            }
            TranscriptionMode.MANUAL -> {
                workManager.cancelUniqueWork(AFTER_STOP)
                workManager.cancelUniqueWork(ON_CHARGER)
            }
            TranscriptionMode.OFF -> workManager.cancelAllWorkByTag(TAG)
        }
    }

    /** Recognises speech in all events of [sleepId] again, now, whatever the mode. */
    fun runNow(sleepId: Long) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(TranscriptionWorker.KEY_SLEEP to sleepId))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork("$MANUAL$sleepId", ExistingWorkPolicy.REPLACE, request)
    }

    /** The run after the stop paused for the battery: the rest waits for the charger. */
    internal fun waitForCharger() {
        pausedForBattery.value = true
        enqueue(ON_CHARGER, charging = true)
    }

    internal fun runStarted() {
        pausedForBattery.value = false
    }

    private fun enqueue(name: String, charging: Boolean) {
        val request = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(workDataOf(TranscriptionWorker.KEY_PAUSE to !charging))
            .setConstraints(Constraints(requiresCharging = charging))
            .addTag(TAG)
            .build()
        // A running job may have looked for waiting events already; the new one runs after it.
        workManager.enqueueUniqueWork(name, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    private companion object {
        const val AFTER_STOP = "transcription-after-stop"
        const val ON_CHARGER = "transcription-on-charger"
        const val MANUAL = "transcription-sleep-"

        /** Every speech recognition job carries it, so switching speech recognition off finds them all. */
        const val TAG = "transcription"
    }
}

/** The phone's battery as [TranscriptionRunner] needs it. */
fun batteryState(context: Context): BatteryState {
    val battery = context.getSystemService(BatteryManager::class.java)
    return BatteryState(battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY), battery.isCharging)
}

/**
 * One speech recognition run: all events of one sleep, or every waiting event. It runs as a
 * foreground service with a progress notification when Android allows one, else as normal work
 * that Android may stop after ten minutes; the next run continues.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SleepTalkApplication).container
        val sleepId = inputData.getLong(KEY_SLEEP, NO_SLEEP)
        val request = if (sleepId == NO_SLEEP) {
            TranscriptionRequest.Waiting(pauseOnLowBattery = inputData.getBoolean(KEY_PAUSE, false))
        } else {
            TranscriptionRequest.Sleep(sleepId)
        }
        container.transcriptionScheduler.runStarted()

        val notifications = TranscriptionNotifications(applicationContext)
        val runner = container.transcriptionRunner
        val started = System.nanoTime()
        val outcome = coroutineScope {
            val shown = launch {
                var foreground = false
                runner.progress.filterNotNull().collect { progress ->
                    if (!foreground) {
                        foreground = try {
                            setForeground(notifications.foregroundInfo(progress))
                            true
                        } catch (_: IllegalStateException) {
                            // Android refuses a foreground service to a job started in the
                            // background; the job then runs on as normal work.
                            false
                        }
                    } else {
                        notifications.show(progress)
                    }
                }
            }
            try {
                runner.run(request) { container.openSpeechRecognition() }
            } finally {
                shown.cancel()
            }
        }
        Log.i(TAG, "Speech recognition run ($request) ended with $outcome after ${(System.nanoTime() - started) / 1_000_000_000} s")

        if (outcome == TranscriptionRunner.Outcome.LOW_BATTERY) container.transcriptionScheduler.waitForCharger()
        return Result.success()
    }

    companion object {
        internal const val KEY_SLEEP = "sleep"
        internal const val KEY_PAUSE = "pause_on_low_battery"
        private const val NO_SLEEP = -1L
        private const val TAG = "Transcription"
    }
}

/** The progress notification of speech recognition. */
private class TranscriptionNotifications(context: Context) {

    private val progress = ProgressNotifications(
        context,
        NotificationIds.TRANSCRIPTION,
        channelId = "transcription",
        channelName = R.string.notification_channel_transcription,
        channelDescription = R.string.notification_channel_transcription_description,
    )

    fun foregroundInfo(progress: TranscriptionProgress) = this.progress.foregroundInfo(notification(progress))

    fun show(progress: TranscriptionProgress) {
        this.progress.show(notification(progress))
    }

    private fun notification(progress: TranscriptionProgress): Notification {
        val texts = this.progress.texts
        return this.progress.build {
            setSmallIcon(R.drawable.ic_record_voice)
            setContentTitle(texts.getString(R.string.notification_transcription_title))
            setContentText(
                texts.resources.getQuantityString(R.plurals.notification_transcription_progress, progress.total, progress.done, progress.total),
            )
            setProgress(progress.total, progress.done, false)
        }
    }
}
