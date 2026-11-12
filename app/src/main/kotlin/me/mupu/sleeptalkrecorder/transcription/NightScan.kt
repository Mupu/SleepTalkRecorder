package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine

/**
 * Whether the scan during the night may run now: only while the setting is [enabled], and then
 * under the same conditions as speech recognition in [mode]. After the stop it runs while the
 * battery has at least 30 % or the phone charges; while charging only on the charger; manually or
 * with speech recognition off it does not run, because nothing runs on its own then.
 */
fun nightScanMayRun(enabled: Boolean, mode: TranscriptionMode, battery: BatteryState): Boolean = enabled && when (mode) {
    TranscriptionMode.AFTER_STOP -> battery.charging || battery.percent >= TranscriptionRunner.LOW_BATTERY_PERCENT
    TranscriptionMode.WHILE_CHARGING -> battery.charging
    TranscriptionMode.MANUAL, TranscriptionMode.OFF -> false
}

/**
 * The scan during the night: while a sleep is recorded and the setting is on, the second way of
 * finding events searches what is recorded so far and recognises speech in its events
 * ([TranscriptionRequest.Night]), whenever its conditions hold ([nightScanMayRun]). It looks every
 * [CHECK_EVERY] whether a part became final; a part is searched only once it is, so most looks
 * read nothing but the recording's length.
 *
 * It runs in the process that records, so nothing that goes wrong may end that process: a run that
 * fails is reported to [onFailure] and the next look tries again. A sound model that cannot be
 * loaded ends the scan for the night, and a speech model that cannot be loaded ends its speech
 * recognition for the night, so neither is loaded again every minute.
 */
class NightScan(
    private val runner: TranscriptionRunner,
    private val enabled: Flow<Boolean>,
    private val mode: Flow<TranscriptionMode>,
    private val battery: () -> BatteryState,
    private val open: suspend () -> EventAnalysis?,
    private val onFailure: (message: String, error: Throwable) -> Unit,
) {
    /** Scans the sleep [sleepId] until cancelled, which the end of its recording does. */
    suspend fun follow(sleepId: Long) {
        val failures = HashMap<Stretch, Int>()
        var speechUnusable = false
        val speech: suspend () -> EventAnalysis? = analysis@{
            if (speechUnusable) return@analysis null
            try {
                open() ?: null.also { speechUnusable = true }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                // Also an error of the native runtime.
                speechUnusable = true
                onFailure("Cannot load speech recognition for the night of sleep $sleepId", error)
                null
            }
        }
        combine(enabled, mode, ::Pair).collectLatest { (on, mode) ->
            // A run under way stops as soon as the setting or the mode no longer allow it.
            if (!nightScanMayRun(on, mode, BatteryState(percent = 100, charging = true))) return@collectLatest
            while (true) {
                if (nightScanMayRun(on, mode, battery())) {
                    try {
                        runner.run(TranscriptionRequest.Night(sleepId, failures) { nightScanMayRun(on, mode, battery()) }, speech)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (unusable: ModelUnusable) {
                        onFailure("The scan during the night of sleep $sleepId ends for this night", unusable)
                        awaitCancellation()
                    } catch (error: Exception) {
                        onFailure("The scan during the night of sleep $sleepId failed; it tries again", error)
                    }
                }
                delay(CHECK_EVERY.toMillis())
            }
        }
    }

    internal companion object {
        /** How often it looks whether it can go on. */
        val CHECK_EVERY: Duration = Duration.ofMinutes(1)
    }
}
