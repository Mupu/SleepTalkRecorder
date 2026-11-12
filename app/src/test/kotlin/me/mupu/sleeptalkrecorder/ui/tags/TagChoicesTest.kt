package me.mupu.sleeptalkrecorder.ui.tags

import me.mupu.sleeptalkrecorder.data.EventKinds
import me.mupu.sleeptalkrecorder.data.KindColor
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.ThemeRole
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class TagChoicesTest {

    private fun event(vararg tags: Long) =
        SleepEvent(1, Duration.ZERO, Duration.ofSeconds(1), EventKinds.EVENT, null, KindColor.Theme(ThemeRole.PRIMARY), tagIds = tags.toSet())

    private val tags = listOf(1L, 7L, 9L)

    @Test
    fun `a tag on some of the events goes to all, then to none and all again, never back to the dash`() {
        val choices = TagChoices.of(tags, listOf(event(1, 7), event(1)), TagChanges())
        assertEquals(listOf(TagState.ON, TagState.MIXED, TagState.OFF), tags.map(choices::stateOf))

        val taps = generateSequence(choices) { it.tap(7) }.take(5).map { it.stateOf(7) }.toList()
        assertEquals(listOf(TagState.MIXED, TagState.ON, TagState.OFF, TagState.ON, TagState.OFF), taps)
    }

    @Test
    fun `a tag on some of the events opens as a dash whatever the last changes say, and they are kept`() {
        val last = TagChanges(added = setOf(7), removed = setOf(1))
        val choices = TagChoices.of(tags, listOf(event(1, 7), event(1)), last)

        assertEquals(listOf(TagState.OFF, TagState.MIXED, TagState.OFF), tags.map(choices::stateOf))
        assertEquals(TagChanges(removed = setOf(1)), choices.changes)
        assertEquals(last, choices.remembered)
    }

    @Test
    fun `OK gives the tags chosen to all events and takes those cleared from all, the others stay`() {
        val choices = TagChoices.of(tags, listOf(event(1, 7), event(1)), TagChanges()).tap(1).tap(9)

        assertEquals(TagChanges(added = setOf(9), removed = setOf(1)), choices.changes)
    }

    @Test
    fun `the window opens with the last changes and keeps them when the user leaves them`() {
        val last = TagChanges(added = setOf(9), removed = setOf(1, 5))
        val choices = TagChoices.of(tags, listOf(event(9)), last)

        assertEquals(listOf(TagState.OFF, TagState.OFF, TagState.ON), tags.map(choices::stateOf))
        // Tag 5 no longer exists, and the event had the others' states already.
        assertEquals(TagChanges(added = setOf(9), removed = setOf(1)), choices.remembered)
        assertEquals(TagChanges(), choices.changes)

        val changed = choices.tap(9).tap(7)
        assertEquals(TagChanges(added = setOf(7), removed = setOf(1, 9)), changed.remembered)
    }

    @Test
    fun `a tag deleted while the window is open is neither applied nor remembered`() {
        val choices = TagChoices.of(tags, listOf(event(9)), TagChanges(added = setOf(7))).tap(1).without(7)

        assertEquals(TagChanges(added = setOf(1)), choices.changes)
        assertEquals(TagChanges(added = setOf(1)), choices.remembered)
    }

    @Test
    fun `the last changes survive being stored as text`() {
        val changes = TagChanges(added = setOf(1, 12), removed = setOf(7))

        assertEquals(changes, TagChanges.parse(changes.format()))
        assertEquals(TagChanges(), TagChanges.parse(null))
    }
}
