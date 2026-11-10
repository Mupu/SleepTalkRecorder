package me.mupu.sleeptalkrecorder.ui.settings

import me.mupu.sleeptalkrecorder.ui.components.UpdateAction
import me.mupu.sleeptalkrecorder.ui.components.action
import me.mupu.sleeptalkrecorder.update.UpdateProblem
import me.mupu.sleeptalkrecorder.update.UpdateState
import me.mupu.sleeptalkrecorder.whatsnew.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatesUiStateTest {

    private val version = Version(0, 5, 1)

    private fun offersCheck(update: UpdateState) = UpdatesUiState(checkAutomatically = true, update = update).offersCheck

    @Test
    fun `a found update offers its download or install instead of Check now`() {
        assertEquals(UpdateAction.DOWNLOAD, UpdateState.Available(version, 1_000).action)
        assertEquals(UpdateAction.INSTALL, UpdateState.Ready(version).action)
        assertEquals(UpdateAction.DOWNLOAD, UpdateState.Failed(UpdateProblem.DAMAGED, version).action)
        assertEquals(UpdateAction.INSTALL, UpdateState.Failed(UpdateProblem.INSTALL_FAILED, version).action)

        assertFalse(offersCheck(UpdateState.Available(version, 1_000)))
        assertFalse(offersCheck(UpdateState.Ready(version)))
        assertFalse(offersCheck(UpdateState.Failed(UpdateProblem.DOWNLOAD_FAILED, version)))
    }

    @Test
    fun `nothing is offered while the update downloads or installs`() {
        assertNull(UpdateState.Downloading(version, 10, 100).action)
        assertNull(UpdateState.Installing(version).action)

        assertFalse(offersCheck(UpdateState.Downloading(version, 10, 100)))
        assertFalse(offersCheck(UpdateState.Installing(version)))
    }

    @Test
    fun `without an update to act on only Check now is offered`() {
        val withoutAction = listOf(
            UpdateState.Unchecked,
            UpdateState.UpToDate,
            UpdateState.Failed(UpdateProblem.CHECK_FAILED, null),
            UpdateState.Failed(UpdateProblem.OTHER_SIGNER, version),
        )

        withoutAction.forEach {
            assertNull(it.action)
            assertTrue(offersCheck(it))
        }
    }
}
