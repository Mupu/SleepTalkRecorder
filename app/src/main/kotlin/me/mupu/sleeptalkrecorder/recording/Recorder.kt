package me.mupu.sleeptalkrecorder.recording

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the recorder is doing, as the UI shows it. */
sealed interface RecorderState {

    /** Nothing is recorded. */
    data object Idle : RecorderState

    /** A recording was asked for and the microphone is being opened. */
    data object Starting : RecorderState

    /** A sleep is being recorded; [progress] is null until the first audio arrives. */
    data class Recording(val progress: RecordingProgress?) : RecorderState

    /** The last recording could not start or ended on its own. */
    data class Failed(val reason: RecorderProblem) : RecorderState
}

/** Why the recorder is not recording although it was asked to. */
enum class RecorderProblem {
    /** The microphone permission is missing, or Android did not let the recording start. */
    NOT_ALLOWED,

    /** The microphone could not be opened or stopped delivering audio. */
    MICROPHONE_UNAVAILABLE,

    /** Audio could not be written, for example because the storage is full. */
    STORAGE_FAILED,
}

/**
 * The app's single entry point for starting and stopping a recording, and the place its state is
 * published. The recording itself runs in [RecordingService], so that it keeps running with the
 * screen off and the app in the background.
 */
class Recorder(private val context: Context) {

    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)

    /** The current state; updated about ten times a second while recording. */
    val state: StateFlow<RecorderState> = mutableState.asStateFlow()

    /**
     * Starts recording a new sleep. Android lets a microphone service start only while the app is
     * visible (or from a notification), so call this from the UI.
     */
    fun start() {
        if (mutableState.value is RecorderState.Recording || mutableState.value == RecorderState.Starting) return

        // Checked here because a microphone service that cannot go into the foreground would be
        // stopped by Android as a crash.
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            mutableState.value = RecorderState.Failed(RecorderProblem.NOT_ALLOWED)
            return
        }

        mutableState.value = RecorderState.Starting
        try {
            context.startForegroundService(RecordingService.startIntent(context))
        } catch (_: ForegroundServiceStartNotAllowedException) {
            mutableState.value = RecorderState.Failed(RecorderProblem.NOT_ALLOWED)
        }
    }

    /** Stops the running recording; its sleep is then finished and listed in the library. */
    fun stop() {
        if (mutableState.value is RecorderState.Recording || mutableState.value == RecorderState.Starting) {
            context.startService(RecordingService.stopIntent(context))
        }
    }

    internal fun report(state: RecorderState) {
        mutableState.value = state
    }
}
