package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.models.TranscriptionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelChangeTest {

    private val parakeet = TranscriptionModel.PARAKEET_ULTRA
    private val whisper = TranscriptionModel.WHISPER_SMALL

    @Test
    fun `transcripts of another model than the active one offer a re-run with the active one`() {
        assertEquals(ModelChange("whisper-small", parakeet), ModelChange.of(mapOf("whisper-small" to 12), parakeet))
    }

    @Test
    fun `a re-run cut short still offers the rest, naming the model of most of the others`() {
        val change = ModelChange.of(mapOf("parakeet-ultra" to 5, "whisper-small" to 3, "old-model" to 1), parakeet)

        assertEquals(ModelChange("whisper-small", parakeet), change)
    }

    @Test
    fun `nothing is offered when the active model made every transcript, or there is none or no model`() {
        assertNull(ModelChange.of(mapOf("whisper-small" to 7), whisper))
        assertNull(ModelChange.of(emptyMap(), whisper))
        assertNull(ModelChange.of(mapOf("whisper-small" to 7), active = null))
    }
}
