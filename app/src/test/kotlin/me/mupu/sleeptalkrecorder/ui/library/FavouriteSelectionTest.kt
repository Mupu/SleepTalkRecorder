package me.mupu.sleeptalkrecorder.ui.library

import me.mupu.sleeptalkrecorder.data.KindColor
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.data.ThemeRole
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.time.SleepKind
import me.mupu.sleeptalkrecorder.ui.share.EventSelection
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Test

class FavouriteSelectionTest {

    private fun group(sleepId: Long, vararg ids: Long) = FavouriteGroup(
        SleepSummary(sleepId, Instant.EPOCH, Instant.EPOCH, SleepDate(SleepKind.NIGHT, LocalDate.EPOCH), 1, ids.size, 0, eventsFound = true),
        ids.map { id ->
            FavouriteItem(sleepId, SleepEvent(id, Duration.ofSeconds(id), Duration.ofSeconds(id + 1), "event", null, KindColor.Theme(ThemeRole.PRIMARY)), at = null)
        },
    )

    @Test
    fun `a selection spans several sleeps in the list's order and drops favourites that are gone`() {
        val groups = listOf(group(2, 21, 22), group(1, 11))
        val selection = EventSelection().toggle(groups[1].favourites[0].event).toggle(groups[0].favourites[1].event)

        assertEquals(listOf(22L, 11L), selectedFavourites(groups, selection).map { it.event.id })
        assertEquals(listOf(22L), selectedFavourites(groups.take(1), selection).map { it.event.id })
    }
}
