package me.mupu.sleeptalkrecorder.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import androidx.work.ForegroundInfo
import me.mupu.sleeptalkrecorder.MainActivity

/** The id of every notification the app shows, in one place so that no two share one. */
object NotificationIds {
    /** The running recording, which is also the recording service's foreground notification. */
    const val RECORDING = 1

    /** A recording stopped on its own at the latest stop time. */
    const val RECORDING_STOPPED = 2

    /** The progress of a model download. */
    const val MODEL_DOWNLOAD = 3

    /** The progress of speech recognition. */
    const val TRANSCRIPTION = 4
}

/** Brings the app to the front as it was, from a tap on one of its notifications. */
fun openAppIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE,
)

/**
 * The progress notification [id] of a WorkManager job that runs as a foreground service of type
 * `dataSync`, on a channel of its own: it opens the app, stays while the job runs and alerts
 * only once.
 */
class ProgressNotifications(
    private val context: Context,
    private val id: Int,
    private val channelId: String,
    @StringRes channelName: Int,
    @StringRes channelDescription: Int,
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    /** The app's texts in the per-app language, which Android 12 applies to activities only. */
    val texts: Context = ContextCompat.getContextForLanguage(context)

    init {
        // Low: shown in the shade without a sound; repeated calls keep what the user changed.
        manager.createNotificationChannel(
            NotificationChannel(channelId, texts.getString(channelName), NotificationManager.IMPORTANCE_LOW)
                .apply { description = texts.getString(channelDescription) },
        )
    }

    /** A progress notification of the channel, to which [content] adds its icon, texts, progress and actions. */
    fun build(content: Notification.Builder.() -> Unit): Notification = Notification.Builder(context, channelId)
        .setContentIntent(openAppIntent(context))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .apply(content)
        .build()

    /** What the job passes to `setForeground` to run as a foreground service showing [notification]. */
    fun foregroundInfo(notification: Notification) =
        ForegroundInfo(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** Replaces the shown notification with [notification]. */
    fun show(notification: Notification) {
        manager.notify(id, notification)
    }
}
