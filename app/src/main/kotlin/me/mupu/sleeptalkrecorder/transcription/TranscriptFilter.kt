package me.mupu.sleeptalkrecorder.transcription

/** What the speech recognition makes of an event. */
sealed interface Verdict {
    /** Words were recognised: a speech event with a transcript. */
    data class Speech(val text: String) : Verdict

    /** Only fillers such as "mhm" or "uh": a speech event without words. */
    data object Murmur : Verdict

    /** Nothing, or text the model invents on non-speech: the event stays a plain event. */
    data object NoSpeech : Verdict
}

/**
 * Decides from a model's raw output whether an event is a speech event. The rules are the ones the
 * models were measured with on the test nights, applied in this order:
 *
 * 1. Tags such as `[music]`, `(laughs)`, `*lacht*`, `<|nospeech|>` and music notes are removed.
 * 2. Outputs of six words or fewer that contain a phrase models write on silence or noise
 *    ("thank you for watching", "Untertitel", ...) are dropped.
 * 3. Repetition loops (six words or more, fewer than 35 % of them different) are dropped.
 * 4. Outputs that are only fillers make a murmur.
 * 5. Anything else is speech.
 *
 * On the measured nights these rules left words on about 1 to 2 % of the events that held no
 * speech, while the error rate on speech did not change.
 */
object TranscriptFilter {

    /** Changes with the rules, so the results can be told apart from those of other rules. */
    const val VERSION = 1

    fun judge(output: String): Verdict {
        val text = TAG.replace(output, " ").replace(SPACE, " ").trim()
        val words = WORD.findAll(text).map { it.value.lowercase() }.toList()
        if (words.isEmpty()) return Verdict.NoSpeech

        if (words.size <= HALLUCINATION_MAX_WORDS && HALLUCINATION.containsMatchIn(text)) return Verdict.NoSpeech
        if (words.size >= LOOP_MIN_WORDS && words.toSet().size < LOOP_MAX_UNIQUE_SHARE * words.size) return Verdict.NoSpeech

        val letters = words.flatMap { it.split('-', '\'') }.filter { part -> part.any(Char::isLetter) }
        if (letters.isEmpty()) return Verdict.NoSpeech
        if (letters.all { FILLER.matches(it) }) return Verdict.Murmur
        return Verdict.Speech(text)
    }

    /** `[…]`, `(…)`, `*…*`, music notes and Whisper's special tokens. */
    private val TAG = Regex("""\[[^\]]*]|\([^)]*\)|\*[^*]*\*|[♪♫]+|<\|[^|]*\|>""")
    private val SPACE = Regex("""\s+""")

    /** A word: letters and digits, with inner hyphens and apostrophes ("mm-hmm", "don't"). */
    private val WORD = Regex("""[\p{L}\p{N}]+(?:['’-][\p{L}\p{N}]+)*""")

    /** Sounds without words, alone or together; also the plain agreement words models write for them. */
    private val FILLER = Regex(
        "m+|m+h?m+|hmm+|h+m+|uh+|um+|o+h*|a+h*|eh+|huh|(ha)+|yeah|yep|okay|ok|嗯+|哦+|啊+|呃+",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Phrases models write on silence or noise, learned from subtitles and video sign-offs. A
     * sentence of more than [HALLUCINATION_MAX_WORDS] words is kept even when it holds one.
     */
    private val HALLUCINATION = Regex(
        listOf(
            "untertitel", "amara", "thank(s| you)( so much)?( for watching)?", "bis zum n[aä]chsten mal",
            "vielen dank", "danke( sch[oö]n)?", "tsch[uü]ss", "copyright", "swr", "zdf", "subtitles?",
            "please subscribe", "like and subscribe", "^\\W*(you|bye)\\W*$",
        ).joinToString("|"),
        RegexOption.IGNORE_CASE,
    )

    private const val HALLUCINATION_MAX_WORDS = 6
    private const val LOOP_MIN_WORDS = 6
    private const val LOOP_MAX_UNIQUE_SHARE = 0.35
}
