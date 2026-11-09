package me.mupu.sleeptalkrecorder.recording

import me.mupu.sleeptalkrecorder.time.SleepDate
import java.time.Instant

/** Where a running recording keeps its sleep and its progress. */
interface RecordingStore {

    /**
     * Starts a recording at [startedAt] (until [anchor] corrects it): the next recording of the
     * last sleep when the sleep windows give it the same name, otherwise the first of a new sleep,
     * named by the sleep windows at this moment.
     */
    suspend fun startRecording(startedAt: Instant, sampleRate: Int): NewRecording

    /** Sets the instant of the recording's first sample, as the audio clock reports it. */
    suspend fun anchor(recordingId: Long, startedAt: Instant)

    /** Records that [frames] samples of the recording are safely on storage. */
    suspend fun saveProgress(recordingId: Long, frames: Long)

    /** Ends the recording with [frames] samples, and its sleep with it. */
    suspend fun finish(recordingId: Long, frames: Long)

    /**
     * Records that the microphone gave the recording silence from sample [fromFrame] until
     * [untilFrame] (exclusive). A later call with the same [fromFrame] extends that stretch, so an
     * open stretch can be saved at every flush.
     */
    suspend fun markMicrophoneUnavailable(recordingId: Long, fromFrame: Long, untilFrame: Long)
}

/** The ids of a recording started with [RecordingStore.startRecording] and of its sleep, with the sleep's name. */
data class NewRecording(val sleepId: Long, val recordingId: Long, val sleepDate: SleepDate)
