package me.mupu.sleeptalkrecorder.readiness

import org.junit.Assert.assertEquals
import org.junit.Test

class PermissionPromptTest {

    @Test
    fun `a permission never asked for, or refused once, is asked for in the system dialog`() {
        assertEquals(PermissionPrompt.DIALOG, PermissionPrompt.of(askedBefore = false, systemWouldExplain = false))
        assertEquals(PermissionPrompt.DIALOG, PermissionPrompt.of(askedBefore = true, systemWouldExplain = true))
    }

    @Test
    fun `a permission the system no longer asks for opens the app's settings`() {
        assertEquals(PermissionPrompt.SETTINGS, PermissionPrompt.of(askedBefore = true, systemWouldExplain = false))
    }
}
