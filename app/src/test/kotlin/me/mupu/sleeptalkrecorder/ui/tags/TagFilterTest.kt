package me.mupu.sleeptalkrecorder.ui.tags

import me.mupu.sleeptalkrecorder.data.EventKinds
import me.mupu.sleeptalkrecorder.data.EventTag
import me.mupu.sleeptalkrecorder.data.KindColor
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.Sorting
import me.mupu.sleeptalkrecorder.data.Tags
import me.mupu.sleeptalkrecorder.data.ThemeRole
import me.mupu.sleeptalkrecorder.detection.FoundBy
import me.mupu.sleeptalkrecorder.detection.Verdict
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class TagFilterTest {

    private fun event(id: Long, kind: String = EventKinds.NOISE, tags: Set<Long> = emptySet(), hidden: Boolean = false, name: String? = null) =
        SleepEvent(id, Duration.ofSeconds(id), Duration.ofSeconds(id + 1), kind, null, KindColor.Theme(ThemeRole.PRIMARY), hidden = hidden, tagIds = tags, name = name)

    private val events = listOf(
        event(1),
        event(2, EventKinds.SPEECH),
        event(3, tags = setOf(Tags.FAVOURITE)),
        event(4, tags = setOf(7)),
        event(5, tags = setOf(Tags.FAVOURITE), hidden = true),
        event(6, name = "Laughing"),
        event(7, EventKinds.MURMUR),
    )

    private fun listed(vararg chosen: EventTag) = events.filter(TagFilter(chosen.toSet())::shows).map { it.id }

    @Test
    fun `without tags every event that is not hidden is listed`() {
        assertEquals(listOf(1L, 2L, 3L, 4L, 6L, 7L), listed())
    }

    @Test
    fun `several tags list the events with any of them, hidden ones only with hidden`() {
        assertEquals(listOf(2L, 3L, 4L), listed(EventTag.Stored(Tags.FAVOURITE), EventTag.Stored(7), EventTag.System.Kind(EventKinds.SPEECH)))
        assertEquals(listOf(3L, 5L, 6L), listed(EventTag.Stored(Tags.FAVOURITE), EventTag.System.Hidden, EventTag.System.Named))
    }

    @Test
    fun `background of the new way is listed only while background is chosen`() {
        val background = event(8).copy(sorting = Sorting(Verdict.BACKGROUND, FoundBy.LOUDNESS, 0.01f, 3f))
        val unsure = event(9).copy(sorting = Sorting(Verdict.UNSURE, FoundBy.LISTENING, 0.1f, 3f))
        val listed = { filter: TagFilter -> listOf(background, unsure).filter(filter::shows).map { it.id } }

        assertEquals(listOf(9L), listed(TagFilter()))
        assertEquals(listOf(9L), listed(TagFilter(setOf(EventTag.System.Kind(EventKinds.NOISE)))))
        assertEquals(listOf(8L), listed(TagFilter(setOf(EventTag.System.Background))))
        assertEquals(listOf(9L), listed(TagFilter(setOf(EventTag.System.Unsure))))
    }

    @Test
    fun `a murmur is listed by its own kind, not by speech`() {
        assertEquals(listOf(7L), listed(EventTag.System.Kind(EventKinds.MURMUR)))
        assertEquals(listOf(2L), listed(EventTag.System.Kind(EventKinds.SPEECH)))
    }
}
