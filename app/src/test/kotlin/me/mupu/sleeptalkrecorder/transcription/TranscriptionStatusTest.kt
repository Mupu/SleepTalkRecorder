package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.TranscriptionCount
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptionStatusTest {

    private fun status(
        count: TranscriptionCount = TranscriptionCount(events = 10, transcribed = 4),
        progress: TranscriptionProgress? = null,
        mode: TranscriptionMode = TranscriptionMode.AFTER_STOP,
        hasModel: Boolean = true,
        batteryPause: Boolean = false,
    ) = TranscriptionStatus.of(sleepId = 1, count, progress, mode, hasModel, batteryPause)

    @Test
    fun `a run on the sleep shows its progress`() {
        assertEquals(TranscriptionStatus.Running(2, 6), status(progress = TranscriptionProgress(1, 2, 6)))
    }

    @Test
    fun `waiting events say why they wait`() {
        assertEquals(WaitReason.QUEUE, (status(progress = TranscriptionProgress(2, 0, 3)) as TranscriptionStatus.Waiting).reason)
        assertEquals(WaitReason.MODEL, (status(hasModel = false, mode = TranscriptionMode.MANUAL) as TranscriptionStatus.Waiting).reason)
        assertEquals(WaitReason.CHARGER, (status(mode = TranscriptionMode.WHILE_CHARGING) as TranscriptionStatus.Waiting).reason)
        assertEquals(WaitReason.BATTERY, (status(batteryPause = true) as TranscriptionStatus.Waiting).reason)
    }

    @Test
    fun `switched off, speech recognition neither waits nor runs`() {
        assertEquals(TranscriptionStatus.Off, status(mode = TranscriptionMode.OFF))
        assertEquals(TranscriptionStatus.Off, status(mode = TranscriptionMode.OFF, progress = TranscriptionProgress(1, 2, 6)))
    }

    @Test
    fun `a sleep whose events all have a result is done`() {
        assertEquals(TranscriptionStatus.Done, status(count = TranscriptionCount(events = 3, transcribed = 3)))
        assertEquals(TranscriptionStatus.NoEvents, status(count = TranscriptionCount(events = 0, transcribed = 0)))
    }
}
