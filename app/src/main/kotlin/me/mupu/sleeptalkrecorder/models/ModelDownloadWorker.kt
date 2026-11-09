package me.mupu.sleeptalkrecorder.models

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import android.os.SystemClock
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.SleepTalkApplication
import me.mupu.sleeptalkrecorder.notifications.NotificationIds
import me.mupu.sleeptalkrecorder.notifications.ProgressNotifications
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

/**
 * Model downloads as WorkManager jobs, one unique job per model. A job waits until the phone is
 * online, and a job that loses the connection is retried with back-off, continuing the files where
 * they stopped; WorkManager keeps the jobs across process deaths and reboots.
 */
class WorkManagerDownloads(context: Context) : ModelDownloads {

    private val workManager = WorkManager.getInstance(context)

    override val states: Flow<Map<TranscriptionModel, ModelState>> = combine(
        TranscriptionModel.entries.map { model ->
            workManager.getWorkInfosForUniqueWorkFlow(workName(model)).map { infos -> model to infos.lastOrNull()?.let(::stateOf) }
        },
    ) { states -> states.mapNotNull { (model, state) -> state?.let { model to it } }.toMap() }

    override fun start(model: TranscriptionModel) {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setInputData(workDataOf(ModelDownloadWorker.KEY_MODEL to model.name))
            .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
            .build()
        // A download already queued or running goes on; a finished or failed one is started anew.
        workManager.enqueueUniqueWork(workName(model), ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel(model: TranscriptionModel) {
        workManager.cancelUniqueWork(workName(model))
    }

    private fun stateOf(info: WorkInfo): ModelState? = when (info.state) {
        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> ModelState.Waiting
        WorkInfo.State.RUNNING -> {
            val total = info.progress.getLong(ModelDownloadWorker.KEY_TOTAL, 0)
            if (total > 0) ModelState.Downloading(info.progress.getLong(ModelDownloadWorker.KEY_DONE, 0), total) else ModelState.Waiting
        }
        WorkInfo.State.FAILED -> ModelState.Failed(
            DownloadFailure.entries.find { it.name == info.outputData.getString(ModelDownloadWorker.KEY_FAILURE) }
                ?: DownloadFailure.NETWORK,
        )
        // Installed or deleted: the files on disk tell.
        WorkInfo.State.SUCCEEDED, WorkInfo.State.CANCELLED -> null
    }

    private fun workName(model: TranscriptionModel) = "model-download-${model.id}"
}

/**
 * Downloads one model with [ModelInstaller], as a foreground service with a progress notification
 * while it can be one, and activates it once it is installed.
 */
class ModelDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val model = TranscriptionModel.entries.find { it.name == inputData.getString(KEY_MODEL) } ?: return Result.failure()
        val container = (applicationContext as SleepTalkApplication).container
        val installer = container.modelInstaller
        val notifications = ModelDownloadNotifications(applicationContext, id)

        setProgress(workDataOf(KEY_DONE to installer.bytesDone(model.id, model.files), KEY_TOTAL to model.bytes))
        val foreground = try {
            setForeground(notifications.foregroundInfo(model, null))
            true
        } catch (_: IllegalStateException) {
            // Android refuses a foreground service to a job started in the background (a retry
            // while the app is closed). The job then runs as normal work, which Android may stop
            // after ten minutes; the next attempt continues where it stopped.
            false
        }

        var lastReport = 0L
        try {
            installer.install(model.id, model.files) { done, total ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastReport >= REPORT_INTERVAL_MS || done == total) {
                    lastReport = now
                    setProgressAsync(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
                    if (foreground) notifications.show(model, done * 100 / total)
                }
            }
        } catch (cancelled: CancellationException) {
            // Cancelled by the user: what was downloaded so far is thrown away. Stopped by the
            // system or a lost constraint: it is kept for the next attempt.
            if (stopReason == WorkInfo.STOP_REASON_CANCELLED_BY_APP) installer.delete(model.id)
            throw cancelled
        } catch (_: DamagedDownloadException) {
            return failure(DownloadFailure.DAMAGED)
        } catch (_: NotEnoughSpaceException) {
            return failure(DownloadFailure.NO_SPACE)
        } catch (_: IOException) {
            return if (runAttemptCount + 1 < MAX_ATTEMPTS) Result.retry() else failure(DownloadFailure.NETWORK)
        }

        container.modelLibrary.installed(model)
        container.scheduleTranscription()
        return Result.success()
    }

    private fun failure(reason: DownloadFailure) = Result.failure(workDataOf(KEY_FAILURE to reason.name))

    companion object {
        internal const val KEY_MODEL = "model"
        internal const val KEY_DONE = "done"
        internal const val KEY_TOTAL = "total"
        internal const val KEY_FAILURE = "failure"

        /** Attempts before a download that keeps losing its connection gives up and says so. */
        private const val MAX_ATTEMPTS = 10

        /** How often progress reaches the screens and the notification, in milliseconds. */
        private const val REPORT_INTERVAL_MS = 500L
    }
}

/** The progress notification of a model download, with a Cancel action. */
private class ModelDownloadNotifications(private val context: Context, private val workId: UUID) {

    private val progress = ProgressNotifications(
        context,
        NotificationIds.MODEL_DOWNLOAD,
        channelId = "model_download",
        channelName = R.string.notification_channel_model,
        channelDescription = R.string.notification_channel_model_description,
    )

    fun foregroundInfo(model: TranscriptionModel, percent: Long?) = progress.foregroundInfo(notification(model, percent))

    fun show(model: TranscriptionModel, percent: Long) {
        progress.show(notification(model, percent))
    }

    private fun notification(model: TranscriptionModel, percent: Long?): Notification {
        val texts = progress.texts
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        return progress.build {
            setSmallIcon(R.drawable.ic_download)
            setContentTitle(texts.getString(R.string.notification_model_title))
            setContentText(model.displayName)
            setSubText(percent?.let { texts.getString(R.string.percent, it.toInt()) })
            setProgress(100, percent?.toInt() ?: 0, percent == null)
            addAction(
                Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_stop), texts.getString(R.string.cancel), cancel).build(),
            )
        }
    }
}
