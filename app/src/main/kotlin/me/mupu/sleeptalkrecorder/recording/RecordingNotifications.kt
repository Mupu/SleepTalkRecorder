package me.mupu.sleeptalkrecorder.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.core.content.ContextCompat
import me.mupu.sleeptalkrecorder.MainActivity
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.ui.format.ClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import java.time.Instant
import java.time.ZoneId

/**
 * The ongoing notification of a running recording, with its Stop action, and the one a recording
 * leaves when it stopped on its own at the latest stop time.
 */
internal class RecordingNotifications(private val context: Context) {

    private val manager = context.getSystemService(NotificationManager::class.java)

    // The texts in the per-app language, which Android 12 applies to activities only.
    private val texts = ContextCompat.getContextForLanguage(context)

    /** Creates the notification channels; repeated calls keep what the user changed on them. */
    fun createChannels() {
        // Low: visible in the shade and the status bar, but never makes a sound. The recording
        // runs at night, and its stop time may be early in the morning.
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(
                    CHANNEL_ID,
                    texts.getString(R.string.notification_channel_recording),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = texts.getString(R.string.notification_channel_recording_description) },
                NotificationChannel(
                    STOPPED_CHANNEL_ID,
                    texts.getString(R.string.notification_channel_stopped),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = texts.getString(R.string.notification_channel_stopped_description) },
            ),
        )
    }

    /** The notification for a recording that started at [startedAt], or is about to start. */
    fun recording(startedAt: Instant?): Notification {
        val stop = PendingIntent.getService(
            context,
            0,
            RecordingService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(texts.getString(R.string.notification_recording_title))
            .setContentIntent(openApp())
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(context, R.drawable.ic_stop),
                    texts.getString(R.string.stop),
                    stop,
                ).build(),
            )

        if (startedAt != null) {
            builder
                .setContentText(sleepTitle(texts.resources, SleepDate.of(startedAt, ZoneId.systemDefault())))
                .setWhen(startedAt.toEpochMilli())
                .setShowWhen(true)
                .setUsesChronometer(true)
        }
        return builder.build()
    }

    /** Replaces the shown notification, for example once the recording's start is known. */
    fun update(notification: Notification) {
        manager.notify(NOTIFICATION_ID, notification)
    }

    /**
     * Tells that the recording stopped on its own at [stoppedAt], the latest stop time, written in
     * the user's time format. It stays until the user dismisses it or starts the next recording.
     */
    fun showStoppedAtLatest(stoppedAt: Instant, clock: ClockFormatter) {
        val notification = Notification.Builder(context, STOPPED_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_alarm_off)
            .setContentTitle(texts.getString(R.string.notification_stopped_title, clock.format(stoppedAt)))
            .setContentText(texts.getString(R.string.notification_stopped_text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setWhen(stoppedAt.toEpochMilli())
            .setShowWhen(true)
            .build()
        manager.notify(STOPPED_NOTIFICATION_ID, notification)
    }

    /** Removes the notification of an earlier automatic stop, which a new recording outdates. */
    fun cancelStoppedAtLatest() {
        manager.cancel(STOPPED_NOTIFICATION_ID)
    }

    private fun openApp() = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val NOTIFICATION_ID = 1
        private const val STOPPED_NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "recording"
        private const val STOPPED_CHANNEL_ID = "recording_stopped"
    }
}
