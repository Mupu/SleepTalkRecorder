package me.mupu.sleeptalkrecorder.retention

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import me.mupu.sleeptalkrecorder.SleepTalkApplication
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Automatic deletion of old sleeps: while [Settings.autoDelete] is on, WorkManager runs
 * [AutoDeleteWorker] once a day, and once more right after the setting or its days change, so a
 * shorter time takes effect at once instead of on the next daily run.
 */
object AutoDeletion {

    private const val DAILY_WORK = "auto-delete-daily"
    private const val NOW_WORK = "auto-delete-now"

    /**
     * Schedules or cancels the work whenever the settings change; call it once per process, in a
     * scope that lives as long as the process. It never completes.
     */
    suspend fun followSettings(context: Context, settings: SettingsStore) {
        val workManager = WorkManager.getInstance(context)

        combine(settings.flow(Settings.autoDelete), settings.flow(Settings.autoDeleteAfter)) { on, _ -> on }
            .collect { on ->
                if (on) {
                    // The worker reads the settings when it runs, so a scheduled request stays valid.
                    workManager.enqueueUniquePeriodicWork(
                        DAILY_WORK,
                        ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<AutoDeleteWorker>(Duration.ofDays(1)).build(),
                    )
                    // Queued behind a run that is under way, which may have read the old settings.
                    workManager.enqueueUniqueWork(
                        NOW_WORK,
                        ExistingWorkPolicy.APPEND_OR_REPLACE,
                        OneTimeWorkRequestBuilder<AutoDeleteWorker>().build(),
                    )
                } else {
                    workManager.cancelUniqueWork(DAILY_WORK)
                    workManager.cancelUniqueWork(NOW_WORK)
                }
            }
    }
}

/**
 * Deletes the sleeps that are past the days the user keeps them (see [SleepRetention]), if
 * automatic deletion is on. A sleep that cannot be deleted completely stays and is tried again on
 * the next run.
 */
class AutoDeleteWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as SleepTalkApplication).container
        val settings = container.settings
        val repository = container.sleepRepository

        if (!settings.flow(Settings.autoDelete).first()) return Result.success()

        val zone = ZoneId.systemDefault()
        val retention = SleepRetention(settings.flow(Settings.autoDeleteAfter).first())
        for (sleep in retention.expired(repository.sleeps().first(), LocalDate.now(zone))) {
            repository.deleteSleep(sleep.id)
        }
        return Result.success()
    }
}
