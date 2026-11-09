package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.data.AnnotationOrigin
import me.mupu.sleeptalkrecorder.data.AnnotationType
import me.mupu.sleeptalkrecorder.data.Producer
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.transcription.TranscriptionRunner.Outcome
import java.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptionRunnerTest {

    private fun event(second: Long) = Stretch(Duration.ofSeconds(second), Duration.ofSeconds(second + 2))

    /** Sleeps with their events; an event is done once it has a stored result. */
    private class FakeStore(val events: Map<Long, List<Stretch>>) : TranscriptStore {
        val stored = mutableListOf<Pair<Long, Stretch>>()
        var recording = false

        override suspend fun sleepsToTranscribe() = events.keys.filter { eventsOf(it, onlyPending = true).isNotEmpty() }

        override suspend fun eventsOf(sleepId: Long, onlyPending: Boolean) =
            events.getValue(sleepId).filter { !onlyPending || (sleepId to it) !in stored }

        override suspend fun clip(sleepId: Long, event: Stretch) = EventClip(sleepId, event, FloatArray(16_000), 16_000)

        override suspend fun store(sleepId: Long, event: Stretch, annotations: List<AnnotationEntity>) {
            stored += sleepId to event
        }

        override suspend fun isRecording() = recording
    }

    /** Counts its clips; calls [onClip] before answering each. */
    private class FakeAnalysis(val onClip: () -> Unit = {}) : EventAnalysis {
        var clips = 0
        var closed = false
        override val origin = AnnotationOrigin.DEVICE
        override val producer = Producer("fake", "1")

        override suspend fun analyse(clip: EventClip): List<AnnotationEntity> {
            clips += 1
            onClip()
            return listOf(
                AnnotationEntity(
                    sleepId = clip.sleepId,
                    type = AnnotationType.TRANSCRIPT,
                    start = clip.event.start,
                    end = clip.event.end,
                    origin = origin,
                ),
            )
        }

        override fun close() {
            closed = true
        }
    }

    private var battery = BatteryState(percent = 80, charging = false)
    private val store = FakeStore(mapOf(1L to listOf(event(10), event(20)), 2L to listOf(event(5))))
    private val failures = mutableListOf<String>()
    private val runner = TranscriptionRunner(store, { battery }) { message, _ -> failures += message }

    @Test
    fun `an automatic run analyses every waiting event of every sleep and closes the model`() = runTest {
        val analysis = FakeAnalysis()

        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = true)) { analysis })
        assertEquals(listOf(1L to event(10), 1L to event(20), 2L to event(5)), store.stored)
        assertEquals(true, analysis.closed)
        assertNull(runner.progress.value)
    }

    @Test
    fun `the model is not loaded when nothing waits`() = runTest {
        runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = true)) { FakeAnalysis() }

        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = true)) { error("loaded") })
    }

    @Test
    fun `without an installed model nothing is analysed`() = runTest {
        assertEquals(Outcome.NO_MODEL, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = true)) { null })
        assertEquals(emptyList<Pair<Long, Stretch>>(), store.stored)
    }

    @Test
    fun `a run that may pause stops below 30 percent unless the phone charges`() = runTest {
        val analysis = FakeAnalysis { battery = BatteryState(percent = 29, charging = false) }

        assertEquals(Outcome.LOW_BATTERY, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = true)) { analysis })
        assertEquals(1, store.stored.size)

        battery = BatteryState(percent = 29, charging = true)
        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = true)) { FakeAnalysis() })
        assertEquals(3, store.stored.size)
    }

    @Test
    fun `waiting events wait while a recording runs`() = runTest {
        store.recording = true

        assertEquals(Outcome.RECORDING, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = false)) { FakeAnalysis() })
        assertEquals(emptyList<Pair<Long, Stretch>>(), store.stored)
    }

    @Test
    fun `a run for one sleep analyses all its events again, also those already done`() = runTest {
        runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = false)) { FakeAnalysis() }
        val analysis = FakeAnalysis()

        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Sleep(1)) { analysis })
        assertEquals(2, analysis.clips)
    }

    @Test
    fun `an event the analysis fails on is skipped and waits for the next run`() = runTest {
        val analysis = FakeAnalysis()
        val failing = object : EventAnalysis by analysis {
            override suspend fun analyse(clip: EventClip): List<AnnotationEntity> {
                if (clip.event == event(10)) error("model failed")
                return analysis.analyse(clip)
            }
        }

        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = false)) { failing })
        assertEquals(listOf(1L to event(20), 2L to event(5)), store.stored)
        assertEquals(1, failures.size)
    }
}
