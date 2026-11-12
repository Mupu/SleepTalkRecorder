package me.mupu.sleeptalkrecorder.detection

/**
 * Tags a short stretch of audio with sound classes, such as an AudioSet tagger (CED through
 * sherpa-onnx is one). It holds its loaded model until it is closed.
 */
interface SoundTagger : AutoCloseable {
    /** The names of the classes, in the order [tag] gives their probabilities. */
    val classNames: List<String>

    /** The probability of every class in [samples], mono from -1 to 1 at [sampleRate] samples per second. */
    fun tag(samples: FloatArray, sampleRate: Int): FloatArray

    override fun close() = Unit
}

/**
 * How human a sound is: the highest probability a tagger gives any AudioSet class a sleeping person
 * makes with voice or breath (speech and shouts, whispers, moans and other vocal sounds, snoring,
 * breathing, coughs). Body movement, traffic, machines and other noise count for nothing. These are
 * the classes of the human kinds the detection pipeline was measured with.
 */
class HumanScore(classNames: List<String>) {

    private val columns: IntArray = classNames.indices.filter { classNames[it] in HUMAN_CLASSES }.toIntArray()

    /** The human score of a tagger's [probabilities], from 0 to 1. */
    fun of(probabilities: FloatArray): Float {
        var best = 0f
        for (column in columns) if (column < probabilities.size && probabilities[column] > best) best = probabilities[column]
        return best
    }

    companion object {
        /** The AudioSet display names of the human classes. */
        val HUMAN_CLASSES: Set<String> = setOf(
            // Speech and shouts.
            "Speech", "Male speech, man speaking", "Female speech, woman speaking", "Child speech, kid speaking",
            "Conversation", "Narration, monologue", "Babbling", "Shout", "Bellow", "Whoop", "Yell", "Battle cry",
            "Children shouting", "Screaming", "Chatter", "Hubbub, speech noise, speech babble",
            // Whispers.
            "Whispering",
            // Moans and other vocal sounds.
            "Laughter", "Baby laughter", "Giggle", "Snicker", "Belly laugh", "Chuckle, chortle", "Crying, sobbing",
            "Baby cry, infant cry", "Whimper", "Wail, moan", "Sigh", "Groan", "Grunt", "Humming", "Singing",
            "Male singing", "Female singing", "Child singing", "Whistling",
            // Snoring and breathing.
            "Snoring", "Snort", "Breathing", "Wheeze", "Gasp", "Pant", "Sniff",
            // Coughs.
            "Cough", "Throat clearing", "Sneeze",
        )
    }
}
