package me.mupu.sleeptalkrecorder.transcription

import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test

class PauseStateTest {

    @Test
    fun `pauses read back as they were stored`() {
        val state = PauseState(setOf(12L, 13L), mapOf(13L to Rerun(Duration.ofMillis(4500), 139)), duringCharge = true)

        assertEquals(state, PauseState.decode(state.encode()))
        assertEquals(PauseState(), PauseState.decode(PauseState().encode()))
    }

    @Test
    fun `a damaged entry is left out`() {
        assertEquals(PauseState(setOf(12L)), PauseState.decode("- p12 px r13@4500"))
    }
}
