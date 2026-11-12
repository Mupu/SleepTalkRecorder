package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.data.AnnotationEntity
import me.mupu.sleeptalkrecorder.data.AnnotationOrigin
import me.mupu.sleeptalkrecorder.data.AnnotationType
import me.mupu.sleeptalkrecorder.data.Producer
import me.mupu.sleeptalkrecorder.playback.Stretch
import me.mupu.sleeptalkrecorder.transcription.TranscriptionRunner.Outcome
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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

    /** Paused sleeps, and where runs over all events stand. */
    private class FakePauses : PauseStore {
        val paused = mutableSetOf<Long>()
        val reruns = mutableMapOf<Long, Rerun>()

        override suspend fun paused() = paused.toSet()

        override suspend fun rerunAt(sleepId: Long, rerun: Rerun?) {
            if (rerun == null) reruns -= sleepId else reruns[sleepId] = rerun
        }
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
    private val pauses = FakePauses()
    private val runner = TranscriptionRunner(store, pauses, { battery }) { message, _ -> failures += message }

    @Test
    fun `a paused sleep is left out from its next event on, and the others go on`() = runTest {
        val analysis = FakeAnalysis { pauses.paused += 1 }

        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = false)) { analysis })
        assertEquals(listOf(1L to event(10), 2L to event(5)), store.stored)
    }

    @Test
    fun `a paused run over all events goes on where it stopped`() = runTest {
        assertEquals(Outcome.PAUSED, runner.run(TranscriptionRequest.Sleep(1)) { FakeAnalysis { pauses.paused += 1 } })
        val rerun = Rerun(event(20).start, left = 1)
        assertEquals(rerun, pauses.reruns[1])

        pauses.paused.clear()
        val analysis = FakeAnalysis()
        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Sleep(1, from = rerun.from)) { analysis })
        assertEquals(1, analysis.clips)
        assertNull(pauses.reruns[1])
    }

    @Test
    fun `a run over all events that is stopped keeps where it was`() = runTest {
        val stopped = runCatching {
            runner.run(TranscriptionRequest.Sleep(1)) { FakeAnalysis { if (store.stored.isNotEmpty()) throw CancellationException("stopped") } }
        }

        assertEquals(true, stopped.exceptionOrNull() is CancellationException)
        assertEquals(Rerun(event(20).start, left = 1), pauses.reruns[1])
    }

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
    fun `a run for one event analyses only that event, also when it is done`() = runTest {
        runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = false)) { FakeAnalysis() }
        store.stored.clear()
        val analysis = FakeAnalysis()

        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Event(1, event(20))) { analysis })
        assertEquals(listOf(1L to event(20)), store.stored)
        assertEquals(true, analysis.closed)
    }

    @Test
    fun `a single event goes before a run in progress, which then goes on without doing it again`() = runTest {
        var opened = 0
        var single: Job? = null
        val outcome = runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = false)) {
            opened += 1
            FakeAnalysis {
                single = single ?: launch(start = CoroutineStart.UNDISPATCHED) {
                    runner.run(TranscriptionRequest.Event(1, event(20))) { FakeAnalysis() }
                }
            }
        }
        single?.join()

        assertEquals(Outcome.FINISHED, outcome)
        assertEquals(listOf(1L to event(10), 1L to event(20), 2L to event(5)), store.stored)
        assertEquals(2, opened)
    }

    @Test
    fun `a run for an event that is no longer found loads no model`() = runTest {
        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Event(1, event(30))) { error("loaded") })
    }

    /** The second way: searches each sleep it has not searched yet, as [outcome] says, and writes down what came first. */
    private class FakeSearch(val log: MutableList<String>, var outcome: SearchOutcome = SearchOutcome.DONE, val onSearch: () -> Unit = {}) : EventSearch {
        val notSearched = mutableListOf(2L, 1L)
        val searched = mutableListOf<Long>()

        override suspend fun sleepsToSearch() = notSearched.toList()

        override suspend fun search(sleepId: Long, again: Boolean, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean): SearchOutcome {
            onSearch()
            if (shouldStop()) return SearchOutcome.STOPPED
            log += "search $sleepId"
            if (outcome == SearchOutcome.DONE) {
                notSearched -= sleepId
                searched += sleepId
            }
            return outcome
        }

        override suspend fun searchNight(sleepId: Long, onProgress: (Float) -> Unit, shouldStop: suspend () -> Boolean): SearchOutcome {
            if (shouldStop()) return SearchOutcome.STOPPED
            log += "night $sleepId"
            return outcome
        }
    }

    private val log = mutableListOf<String>()

    /** The first way's events, writing down each result. */
    private val logged = object : TranscriptStore by store {
        override suspend fun store(sleepId: Long, event: Stretch, annotations: List<AnnotationEntity>) {
            log += "old $sleepId"
            store.store(sleepId, event, annotations)
        }
    }

    /** The second way's own events of sleep 1, writing down each result. */
    private val candidateStore = FakeStore(mapOf(1L to listOf(event(40))))
    private val candidates = object : TranscriptStore by candidateStore {
        override suspend fun store(sleepId: Long, event: Stretch, annotations: List<AnnotationEntity>) {
            log += "new $sleepId"
            candidateStore.store(sleepId, event, annotations)
        }
    }

    @Test
    fun `the first way's events come first, then the search, then the second way's own events`() = runTest {
        val search = FakeSearch(log)
        val runner = TranscriptionRunner(logged, pauses, { battery }, search, candidates) { _, _ -> }

        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = true)) { FakeAnalysis() })
        assertEquals(listOf("old 1", "old 1", "old 2", "search 2", "search 1", "new 1"), log)
        assertNull(runner.progress.value)
        assertNull(runner.newWayProgress.value)
    }

    @Test
    fun `a run during the night searches the sleep being recorded and recognises only the second way's events`() = runTest {
        store.recording = true
        candidateStore.recording = true
        val runner = TranscriptionRunner(logged, pauses, { battery }, FakeSearch(log), candidates) { _, _ -> }

        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Night(1) { true }) { FakeAnalysis() })
        assertEquals(listOf("night 1", "new 1"), log)
    }

    @Test
    fun `a run during the night stops once its conditions no longer hold or the recording ended`() = runTest {
        store.recording = true
        var asked = 0
        val runner = TranscriptionRunner(logged, pauses, { battery }, FakeSearch(log), candidates) { _, _ -> }

        // Allowed until the search is done, then no more: no event is recognised.
        assertEquals(Outcome.NOT_ALLOWED, runner.run(TranscriptionRequest.Night(1) { ++asked <= 2 }) { FakeAnalysis() })
        assertEquals(listOf("night 1"), log)

        store.recording = false
        assertEquals(Outcome.FINISHED, runner.run(TranscriptionRequest.Night(1) { true }) { error("loaded") })
        assertEquals(listOf("night 1"), log)
    }

    @Test
    fun `a piece the analysis fails on is tried at most three times during the night`() = runTest {
        store.recording = true
        val failures = HashMap<Stretch, Int>()
        val broken = FakeAnalysis { error("broken") }
        val runner = TranscriptionRunner(logged, pauses, { battery }, FakeSearch(log), candidates) { _, _ -> }

        repeat(5) { runner.run(TranscriptionRequest.Night(1, failures) { true }) { broken } }

        assertEquals(3, broken.clips)
    }

    @Test
    fun `nothing of the second way runs while the first way's events still wait`() = runTest {
        val search = FakeSearch(log)
        val runner = TranscriptionRunner(logged, pauses, { battery }, search, candidates) { _, _ -> }

        val outcome = runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = true)) { FakeAnalysis { battery = BatteryState(percent = 20, charging = false) } }

        assertEquals(Outcome.LOW_BATTERY, outcome)
        assertEquals(listOf("old 1"), log)
    }

    @Test
    fun `without the sound models the search waits and speech recognition goes on`() = runTest {
        val missing = FakeSearch(log, SearchOutcome.NO_MODEL)

        assertEquals(Outcome.FINISHED, TranscriptionRunner(store, pauses, { battery }, missing) { _, _ -> }.run(TranscriptionRequest.Sleep(1)) { FakeAnalysis() })
        assertEquals(2, store.stored.size)
        assertEquals(emptyList<Long>(), missing.searched)
    }

    @Test
    fun `a sleep searched before is searched again only when asked, as after its settings changed`() = runTest {
        val search = FakeSearch(log)
        search.notSearched.clear()
        val runner = TranscriptionRunner(store, pauses, { battery }, search) { _, _ -> }

        runner.run(TranscriptionRequest.Sleep(1, onlyPending = true)) { FakeAnalysis() }
        assertEquals(emptyList<Long>(), search.searched)

        runner.run(TranscriptionRequest.Sleep(1, onlyPending = true, searchAgain = true)) { FakeAnalysis() }
        assertEquals(listOf(1L), search.searched)
    }

    @Test
    fun `a search stops for a low battery like speech recognition`() = runTest {
        store.stored += listOf(1L to event(10), 1L to event(20), 2L to event(5))
        val search = FakeSearch(log) { battery = BatteryState(percent = 20, charging = false) }
        val runner = TranscriptionRunner(store, pauses, { battery }, search) { _, _ -> }

        assertEquals(Outcome.LOW_BATTERY, runner.run(TranscriptionRequest.Waiting(pauseOnLowBattery = true)) { error("loaded") })
        assertEquals(emptyList<Long>(), search.searched)
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
