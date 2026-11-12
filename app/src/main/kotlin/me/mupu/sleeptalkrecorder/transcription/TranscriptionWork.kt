package me.mupu.sleeptalkrecorder.transcription

import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.BatteryManager
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.SleepTalkApplication
import me.mupu.sleeptalkrecorder.notifications.NotificationIds
import me.mupu.sleeptalkrecorder.notifications.ProgressNotifications
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs speech recognition as WorkManager jobs, which survive the app being closed and the process
 * being killed; results are kept per event, so a stopped job continues where it stopped.
 *
 * - [TranscriptionMode.AFTER_STOP]: a job without constraints that pauses itself below 30 %
 *   battery and hands over to a job that waits for the charger.
 * - [TranscriptionMode.WHILE_CHARGING]: only the job that waits for the charger; WorkManager stops
 *   it when the charger is unplugged and runs it again on the next one.
 * - [TranscriptionMode.MANUAL]: no job of its own; [run] starts one for a sleep.
 * - [TranscriptionMode.OFF]: no job at all; switching it off cancels every job, also those
 *   started for a sleep.
 *
 * In [TranscriptionMode.WHILE_CHARGING] the user's pauses end on the next charge, which
 * [watchCharger] waits for.
 */
class TranscriptionScheduler(context: Context) {

    private val workManager = WorkManager.getInstance(context)
    private val pausedForBattery = MutableStateFlow(false)

    /** Whether the last run after the stop paused because the battery was low. */
    val batteryPause: StateFlow<Boolean> = pausedForBattery.asStateFlow()

    /** Makes sure waiting events are recognised as [mode] says, or no longer on their own. */
    fun follow(mode: TranscriptionMode) {
        when (mode) {
            TranscriptionMode.AFTER_STOP -> {
                workManager.cancelUniqueWork(CHARGER_WATCH)
                enqueue(AFTER_STOP, charging = false)
            }
            TranscriptionMode.WHILE_CHARGING -> {
                workManager.cancelUniqueWork(AFTER_STOP)
                enqueue(ON_CHARGER, charging = true)
            }
            TranscriptionMode.MANUAL -> {
                workManager.cancelUniqueWork(CHARGER_WATCH)
                workManager.cancelUniqueWork(AFTER_STOP)
                workManager.cancelUniqueWork(ON_CHARGER)
            }
            TranscriptionMode.OFF -> workManager.cancelAllWorkByTag(TAG)
        }
    }

    /** Recognises speech in the events of [request] now, whatever the mode, in place of an earlier run of its sleep. */
    fun run(request: TranscriptionRequest.Sleep) {
        val work = OneTimeWorkRequestBuilder<TranscriptionWorker>()
            .setInputData(
                workDataOf(
                    TranscriptionWorker.KEY_SLEEP to request.sleepId,
                    TranscriptionWorker.KEY_FROM to request.from.toMillis(),
                    TranscriptionWorker.KEY_ONLY_PENDING to request.onlyPending,
                    TranscriptionWorker.KEY_SEARCH_AGAIN to request.searchAgain,
                ),
            )
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork("$MANUAL${request.sleepId}", ExistingWorkPolicy.REPLACE, work)
    }

    /** Lets the next charge end the user's pauses. */
    fun watchCharger() {
        val request = OneTimeWorkRequestBuilder<ChargerWatchWorker>()
            .setConstraints(Constraints(requiresCharging = true))
            .addTag(TAG)
            .build()
        workManager.enqueueUniqueWork(CHARGER_WATCH, ExistingWorkPolicy.KEEP, request)
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
        const val CHARGER_WATCH = "transcription-charger-watch"

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
 * Whether a charger is plugged in. A pause made while one is takes this charge as its own, so it
 * holds until the charger was unplugged; Android may count the phone as charging only some time
 * after it is plugged in, and WorkManager runs a job that needs the charger as soon as it is.
 */
fun isPluggedIn(context: Context): Boolean =
    context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        ?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)?.let { it != 0 } ?: false

/**
 * One speech recognition run: the events of one sleep, or every waiting event, and after them the
 * second way's search and its own events. It runs as a foreground service with a progress
 * notification when Android allows one, else as normal work that Android may stop after ten
 * minutes; the next run continues.
 */
class TranscriptionWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SleepTalkApplication).container
        val sleepId = inputData.getLong(KEY_SLEEP, NO_SLEEP)
        val request = if (sleepId == NO_SLEEP) {
            TranscriptionRequest.Waiting(pauseOnLowBattery = inputData.getBoolean(KEY_PAUSE, false))
        } else {
            val onlyPending = inputData.getBoolean(KEY_ONLY_PENDING, false)
            // Android stopped this job before (a killed process, the ten minutes of normal work):
            // a run over all events goes on where it stopped instead of starting again.
            val stoppedAt = if (runAttemptCount > 0 && !onlyPending) container.transcriptionPauses.rerunOf(sleepId) else null
            TranscriptionRequest.Sleep(
                sleepId,
                from = stoppedAt?.from ?: Duration.ofMillis(inputData.getLong(KEY_FROM, 0)),
                onlyPending = onlyPending,
                searchAgain = inputData.getBoolean(KEY_SEARCH_AGAIN, false),
            )
        }
        container.transcriptionScheduler.runStarted()

        val notifications = TranscriptionNotifications(applicationContext)
        val runner = container.transcriptionRunner
        val started = System.nanoTime()
        val outcome = coroutineScope {
            val shown = launch {
                var foreground = false
                // The first way's run and then the second way's work, which never overlap.
                combine(runner.progress, runner.newWayProgress) { first, second ->
                    first?.let { Shown(it.sleepId, searching = false, it.done, it.total) }
                        ?: second?.let { Shown(it.sleepId, it.searching, it.done, it.total) }
                }.filterNotNull().collect { progress ->
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
        internal const val KEY_FROM = "from_ms"
        internal const val KEY_ONLY_PENDING = "only_pending"
        internal const val KEY_SEARCH_AGAIN = "search_again"
        private const val NO_SLEEP = -1L
        private const val TAG = "Transcription"
    }
}

/**
 * Ends the user's pauses on the next charge. It runs only while the phone charges: after a pause
 * made while not charging, this is the next charge. After one made while charging, it waits until
 * WorkManager stops it because the charger was unplugged, remembers that, and WorkManager runs it
 * again on the next charge.
 */
class ChargerWatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SleepTalkApplication).container
        val pauses = container.transcriptionPauses.state.first()
        if (pauses.paused.isEmpty()) return Result.success()
        if (!pauses.duringCharge) {
            container.transcriptions.resume()
            return Result.success()
        }
        try {
            awaitCancellation()
        } catch (stopped: CancellationException) {
            if (stopReason == WorkInfo.STOP_REASON_CONSTRAINT_CHARGING) {
                withContext(NonCancellable) { container.transcriptionPauses.chargeEnded() }
            }
            throw stopped
        }
    }
}

/** The Pause action of the progress notification: pauses the sleep it names. */
class PauseTranscriptionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val sleepId = intent.getLongExtra(EXTRA_SLEEP, NO_SLEEP)
        if (sleepId == NO_SLEEP) return
        val container = (context.applicationContext as SleepTalkApplication).container
        val result = goAsync()
        container.pauseTranscription(sleepId).invokeOnCompletion { result.finish() }
    }

    internal companion object {
        const val EXTRA_SLEEP = "sleep"
        private const val NO_SLEEP = -1L
    }
}

/** What the notification shows: speech recognition of [sleepId], [done] of [total], or the second way [searching] it, [done] percent. */
private data class Shown(val sleepId: Long, val searching: Boolean, val done: Int, val total: Int)

/** The progress notification of speech recognition, with a Pause action. */
private class TranscriptionNotifications(private val context: Context) {

    private val progress = ProgressNotifications(
        context,
        NotificationIds.TRANSCRIPTION,
        channelId = "transcription",
        channelName = R.string.notification_channel_transcription,
        channelDescription = R.string.notification_channel_transcription_description,
    )

    fun foregroundInfo(progress: Shown) = this.progress.foregroundInfo(notification(progress))

    fun show(progress: Shown) {
        this.progress.show(notification(progress))
    }

    private fun notification(progress: Shown): Notification {
        val texts = this.progress.texts
        return this.progress.build {
            setSmallIcon(R.drawable.ic_record_voice)
            if (progress.searching) {
                setContentTitle(texts.getString(R.string.notification_search_title))
                setContentText(texts.getString(R.string.percent, progress.done))
            } else {
                setContentTitle(texts.getString(R.string.notification_transcription_title))
                setContentText(
                    texts.resources.getQuantityString(R.plurals.notification_transcription_progress, progress.total, progress.done, progress.total),
                )
            }
            setProgress(progress.total, progress.done, false)
            addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_pause),
                    texts.getString(R.string.transcription_pause),
                    pauseIntent(progress.sleepId),
                ).build(),
            )
        }
    }

    private fun pauseIntent(sleepId: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, PauseTranscriptionReceiver::class.java).putExtra(PauseTranscriptionReceiver.EXTRA_SLEEP, sleepId),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
