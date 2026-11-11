package me.mupu.sleeptalkrecorder.transcription

import me.mupu.sleeptalkrecorder.transcription.Verdict.Murmur
import me.mupu.sleeptalkrecorder.transcription.Verdict.NoSpeech
import me.mupu.sleeptalkrecorder.transcription.Verdict.Speech
import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptFilterTest {

    private fun judge(text: String) = TranscriptFilter.judge(text)

    @Test
    fun `words make a speech event with the text as the model wrote it`() {
        assertEquals(Speech("Wo ist der Schlüssel?"), judge(" Wo ist der Schlüssel? "))
    }

    @Test
    fun `nothing or only punctuation is no speech`() {
        assertEquals(NoSpeech, judge(""))
        assertEquals(NoSpeech, judge(" . "))
    }

    @Test
    fun `tags and music notes are removed before judging`() {
        assertEquals(NoSpeech, judge("[music] (thunder rumbling) *lacht* ♪♪ <|nospeech|>"))
        assertEquals(Speech("I said no"), judge("[snoring] I said no"))
    }

    @Test
    fun `a tag whose bracket is never closed is removed`() {
        assertEquals(NoSpeech, judge("(door"))
        assertEquals(NoSpeech, judge("(engine"))
        assertEquals(NoSpeech, judge("[music"))
        assertEquals(NoSpeech, judge("door)"))
        assertEquals(Speech("Where is it"), judge("Where is it (door"))
    }

    @Test
    fun `speech around a complete tag keeps its words`() {
        assertEquals(Speech("Leave the door open, please"), judge("Leave the door (knocking) open, please"))
    }

    @Test
    fun `only fillers make a murmur`() {
        assertEquals(Murmur, judge("Mm-hmm."))
        assertEquals(Murmur, judge("Hmm, uh, oh."))
        assertEquals(Murmur, judge("Okay."))
    }

    @Test
    fun `a filler with a word is speech`() {
        assertEquals(Speech("Hmm, Mama"), judge("Hmm, Mama"))
    }

    @Test
    fun `known hallucinations of six words or fewer are no speech`() {
        assertEquals(NoSpeech, judge("Thank you for watching!"))
        assertEquals(NoSpeech, judge("Untertitel im Auftrag des ZDF"))
        assertEquals(NoSpeech, judge("Vielen Dank."))
        assertEquals(NoSpeech, judge("You"))
        assertEquals(NoSpeech, judge("Bye."))
    }

    @Test
    fun `a hallucination phrase inside a longer sentence is speech`() {
        val text = "Thank you, I will put the keys back on the table"
        assertEquals(Speech(text), judge(text))
    }

    @Test
    fun `a short sentence ending in you is speech`() {
        assertEquals(Speech("I love you"), judge("I love you"))
    }

    @Test
    fun `a repetition loop is no speech`() {
        assertEquals(NoSpeech, judge("the the the the the the the"))
        assertEquals(NoSpeech, judge("ja nein ja nein ja nein ja nein ja nein"))
    }

    @Test
    fun `a short repetition is not a loop`() {
        assertEquals(Speech("no no no"), judge("no no no"))
    }
}
