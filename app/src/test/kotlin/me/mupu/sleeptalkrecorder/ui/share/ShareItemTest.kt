package me.mupu.sleeptalkrecorder.ui.share

import me.mupu.sleeptalkrecorder.data.KindColor
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.ThemeRole
import me.mupu.sleeptalkrecorder.playback.ChunkFile
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.playback.TimelineRecording
import me.mupu.sleeptalkrecorder.time.RecordingClock
import java.io.File
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class ShareItemTest {

    private fun item(id: Long, timeline: SleepTimeline, start: Long = 1) = ShareItem(
        SleepEvent(id, Duration.ofSeconds(start), Duration.ofSeconds(start + 1), "event", null, KindColor.Theme(ThemeRole.PRIMARY)),
        sleep = null,
        timeline,
        unavailable = emptyList(),
    )

    @Test
    fun `an event that its sleep's audio no longer reaches is left out of a share`() {
        val clock = RecordingClock(Instant.EPOCH, 16_000)
        val minute = SleepTimeline(listOf(TimelineRecording(clock, listOf(ChunkFile(File("0.wav"), 16_000 * 60)))))
        // A recording whose chunk files are gone keeps its clock but plays nothing.
        val lost = SleepTimeline(listOf(TimelineRecording(clock, emptyList())))
        val items = listOf(item(1, SleepTimeline(emptyList())), item(2, lost), item(3, minute), item(4, minute, start = 60))

        assertEquals(listOf(3L), playable(items).map { it.event.id })
    }
}
