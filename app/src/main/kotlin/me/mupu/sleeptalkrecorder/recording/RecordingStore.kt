package me.mupu.sleeptalkrecorder.recording

import java.time.Instant

/** Where a running recording keeps its sleep and its progress. */
interface RecordingStore {

    /**
     * Starts a new sleep with its first recording, which starts at [startedAt] until [anchor]
     * corrects it.
     */
    suspend fun startSleep(startedAt: Instant, sampleRate: Int): NewRecording

    /** Sets the instant of the recording's first sample, as the audio clock reports it. */
    suspend fun anchor(recordingId: Long, startedAt: Instant)

    /** Records that [frames] samples of the recording are safely on storage. */
    suspend fun saveProgress(recordingId: Long, frames: Long)

    /** Ends the recording with [frames] samples, and its sleep with it. */
    suspend fun finish(recordingId: Long, frames: Long)
}

/** The ids of a sleep started with [RecordingStore.startSleep] and of its recording. */
data class NewRecording(val sleepId: Long, val recordingId: Long)
