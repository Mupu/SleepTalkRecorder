package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.settings.TranscriptionMode
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class NightScanTest {

    private val low = BatteryState(percent = 29, charging = false)
    private val full = BatteryState(percent = 90, charging = false)
    private val charging = BatteryState(percent = 10, charging = true)

    @Test
    fun `the scan during the night runs under the conditions of speech recognition`() {
        val runs = TranscriptionMode.entries.associateWith { mode -> listOf(low, full, charging).map { nightScanMayRun(enabled = true, mode, it) } }

        assertEquals(listOf(false, true, true), runs[TranscriptionMode.AFTER_STOP])
        assertEquals(listOf(false, false, true), runs[TranscriptionMode.WHILE_CHARGING])
        assertEquals(listOf(false, false, false), runs[TranscriptionMode.MANUAL])
        assertEquals(listOf(false, false, false), runs[TranscriptionMode.OFF])
        assertEquals(false, nightScanMayRun(enabled = false, TranscriptionMode.AFTER_STOP, charging))
    }

    /** A sleep being recorded without events of its own. */
    private val recording = object : TranscriptStore {
        override suspend fun sleepsToTranscribe() = emptyList<Long>()
        override suspend fun eventsOf(sleepId: Long, onlyPending: Boolean) = emptyList<Stretch>()
        override suspend fun clip(sleepId: Long, event: Stretch) = EventClip(sleepId, event, FloatArray(0), 16_000)
        override suspend fun store(sleepId: Long, event: Stretch, annotations: List<AnnotationEntity>) = Unit
        override suspend fun isRecording() = true
    }

    private val pauses = object : PauseStore {
        override suspend fun paused() = emptySet<Long>()
        override suspend fun rerunAt(sleepId: Long, rerun: Rerun?) = Unit
    }

    @Test
    fun `a failed run is reported and the scan goes on, a sound model that cannot be loaded ends it for the night`() = runTest {
        var searches = 0
        val search = object : EventSearch {
            override suspend fun sleepsToSearch() = emptyList<Long>()
            override suspend fun search(sleepId: Long, again: Boolean, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean) = SearchOutcome.DONE
            override suspend fun searchNight(sleepId: Long, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean): SearchOutcome {
                when (++searches) {
                    1 -> error("the audio could not be read")
                    3 -> throw ModelUnusable("no sound models", UnsatisfiedLinkError())
                }
                return SearchOutcome.DONE
            }
        }
        val failures = mutableListOf<String>()
        val runner = TranscriptionRunner(recording, pauses, { full }, search, recording) { _, _ -> }
        val scan = NightScan(runner, flowOf(true), flowOf(TranscriptionMode.AFTER_STOP), { full }, { null }) { message, _ -> failures += message }

        val night = launch { scan.follow(1) }
        advanceTimeBy(NightScan.CHECK_EVERY.multipliedBy(10).toMillis())
        night.cancel()

        assertEquals(3, searches)
        assertEquals(2, failures.size)
    }
}
