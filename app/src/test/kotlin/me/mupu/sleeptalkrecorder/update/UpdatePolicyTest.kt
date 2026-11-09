package me.mupu.sleeptalkrecorder.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdatePolicyTest {

    private val idleInBackground = AppActivity(recording = false, playing = false, transcribing = false, visible = false)

    @Test
    fun `an update downloads on its own only on an unmetered network`() {
        assertTrue(UpdatePolicy.mayDownload(Trigger.APP, metered = false))
        assertFalse(UpdatePolicy.mayDownload(Trigger.APP, metered = true))
        assertTrue(UpdatePolicy.mayDownload(Trigger.USER, metered = true))
    }

    @Test
    fun `nothing installs during a recording, not even on a tap`() {
        val recording = idleInBackground.copy(recording = true)

        assertFalse(UpdatePolicy.mayInstall(Trigger.USER, recording))
        assertFalse(UpdatePolicy.mayInstall(Trigger.APP, recording))
    }

    @Test
    fun `a tap installs while the app is open, also during playback or speech recognition`() {
        val busy = idleInBackground.copy(playing = true, transcribing = true, visible = true)

        assertTrue(UpdatePolicy.mayInstall(Trigger.USER, busy))
    }

    @Test
    fun `the app installs on its own only in the background with nothing running`() {
        assertTrue(UpdatePolicy.mayInstall(Trigger.APP, idleInBackground))

        assertFalse(UpdatePolicy.mayInstall(Trigger.APP, idleInBackground.copy(visible = true)))
        assertFalse(UpdatePolicy.mayInstall(Trigger.APP, idleInBackground.copy(playing = true)))
        assertFalse(UpdatePolicy.mayInstall(Trigger.APP, idleInBackground.copy(transcribing = true)))
    }
}
