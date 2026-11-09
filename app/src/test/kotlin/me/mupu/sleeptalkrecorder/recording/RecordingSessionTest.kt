package me.mupu.sleeptalkrecorder.recording

import java.io.File
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecordingSessionTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val store = FakeStore()
    private val now = Instant.parse("2026-11-08T22:41:12Z")

    @Volatile
    private var wallClock = now
    private val clocks = object : Clocks {
        override fun now() = wallClock
        override fun elapsedNanos() = 12_000_000_000L
    }

    private fun session(source: PcmSource, stopAt: () -> Instant? = { null }) = RecordingSession(
        source = source,
        store = store,
        recordingsDirectory = folder.root,
        clocks = clocks,
        stopAt = stopAt,
        chunkLength = Duration.ofSeconds(1),
        flushInterval = Duration.ofMillis(100),
    )

    @Test
    fun `stopping finishes the recording with every sample that was read`() = runBlocking {
        val source = FakeSource(buffers = 25, bufferFrames = 1_600)

        val job = launch(Dispatchers.IO) { session(source).record {} }
        source.allDelivered.await()
        job.cancelAndJoin()

        val recording = store.recordings.single()
        assertEquals(40_000L, recording.finishedFrames)
        assertEquals(listOf("0001.wav", "0002.wav", "0003.wav"), File(folder.root, "${recording.id}").list()!!.sorted())
        assertEquals(true, source.closed)
    }

    @Test
    fun `the recording starts at the instant the audio clock gives for its first sample`() = runBlocking {
        // After the first buffer the audio clock says sample 1 600 was captured at 10 s since
        // boot; it is now 12 s since boot, so sample 0 was captured 2.1 s before now.
        val source = FakeSource(buffers = 3, bufferFrames = 1_600, timestamp = AudioTimestampSample(1_600, 10_000_000_000))

        val job = launch(Dispatchers.IO) { session(source).record {} }
        source.allDelivered.await()
        job.cancelAndJoin()

        assertEquals(now.minusMillis(2_100), store.recordings.single().startedAt)
    }

    @Test
    fun `a recording that reaches its stop time ends on its own with every sample that was read`() = runBlocking {
        val source = FakeSource(buffers = 3, bufferFrames = 1_600)
        val stopAt = now.plusSeconds(60)

        val result = async(Dispatchers.IO) { session(source, stopAt = { stopAt }).record {} }
        source.allDelivered.await()
        wallClock = stopAt

        assertEquals(RecordingEnd.STOP_TIME_REACHED, result.await())
        assertEquals(4_800L, store.recordings.single().finishedFrames)
    }

    @Test
    fun `a microphone that stops delivering ends the recording with a failure`() = runBlocking {
        val source = FakeSource(buffers = 2, bufferFrames = 1_600, failAfterBuffers = true)

        val result = async(Dispatchers.IO) { session(source).record {} }.await()

        assertEquals(RecordingEnd.MICROPHONE_UNAVAILABLE, result)
        assertEquals(3_200L, store.recordings.single().finishedFrames)
    }

    @Test
    fun `a microphone that cannot be opened leaves no sleep behind`() = runBlocking {
        val source = FakeSource(buffers = 0, bufferFrames = 1_600, startFails = true)

        val result = session(source).record {}

        assertEquals(RecordingEnd.MICROPHONE_UNAVAILABLE, result)
        assertEquals(emptyList<FakeStore.Recording>(), store.recordings)
    }

    @Test
    fun `progress reports the sleep, its samples and the level of the last buffer`() = runBlocking {
        val source = FakeSource(buffers = 2, bufferFrames = 1_600, sample = Short.MAX_VALUE)
        val reports = mutableListOf<RecordingProgress>()

        val job = launch(Dispatchers.IO) { session(source).record { synchronized(reports) { reports += it } } }
        source.allDelivered.await()
        job.cancelAndJoin()

        val last = synchronized(reports) { reports.last() }
        assertEquals(store.recordings.single().sleepId, last.sleepId)
        assertEquals(3_200L, last.frames)
        assertEquals(0.0, last.levelDbfs, 0.01)
    }

    private class FakeSource(
        private val buffers: Int,
        private val bufferFrames: Int,
        private val timestamp: AudioTimestampSample? = null,
        private val sample: Short = 100,
        private val failAfterBuffers: Boolean = false,
        private val startFails: Boolean = false,
    ) : PcmSource {
        override val sampleRate = 16_000
        val allDelivered = CompletableDeferred<Unit>()
        var closed = false
        private var delivered = 0

        override fun start(): Boolean = !startFails

        override fun read(buffer: ShortArray): Int {
            if (delivered == buffers) {
                allDelivered.complete(Unit)
                if (failAfterBuffers) return -1
                Thread.sleep(1)
                return 0
            }
            delivered += 1
            buffer.fill(sample, 0, bufferFrames)
            return bufferFrames
        }

        override fun timestamp(): AudioTimestampSample? = timestamp?.takeIf { delivered > 0 }

        override fun close() {
            closed = true
        }
    }

    private class FakeStore : RecordingStore {
        class Recording(val id: Long, val sleepId: Long, var startedAt: Instant) {
            val progress = mutableListOf<Long>()
            var finishedFrames: Long? = null
        }

        val recordings = mutableListOf<Recording>()

        override suspend fun startSleep(startedAt: Instant, sampleRate: Int): NewRecording {
            val recording = Recording(id = recordings.size + 1L, sleepId = 100L + recordings.size, startedAt = startedAt)
            recordings += recording
            return NewRecording(sleepId = recording.sleepId, recordingId = recording.id)
        }

        override suspend fun anchor(recordingId: Long, startedAt: Instant) {
            recordings.single { it.id == recordingId }.startedAt = startedAt
        }

        override suspend fun saveProgress(recordingId: Long, frames: Long) {
            recordings.single { it.id == recordingId }.progress += frames
        }

        override suspend fun finish(recordingId: Long, frames: Long) {
            recordings.single { it.id == recordingId }.finishedFrames = frames
        }
    }
}
