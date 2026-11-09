package me.mupu.sleeptalkrecorder.detection

/** A stretch of a test night in seconds from the start of its WAV file. */
internal data class Span(val start: Double, val end: Double)

/** A labelled sound of a test night: its [span] and the [kind] of sound the label names. */
internal data class Label(val span: Span, val kind: String)

/** How many labels of one kind there are and how many of them an event found. */
internal data class KindScore(val labels: Int, val found: Int) {
    /** The share of the labels that were found, or null without labels. */
    val share: Double? get() = if (labels == 0) null else found.toDouble() / labels
}

/** How well the events of one night match its labels. */
internal data class NightScore(
    /** The number of detected events. */
    val events: Int,
    /** Detected events that match no label of any kind. */
    val falseEvents: Int,
    /** For each kind in [SPEECH_LIKE] the night has labels of, how many were found. */
    val speechByKind: Map<String, KindScore>,
    /** How long cut playback of the events takes, in seconds. */
    val cutSeconds: Double,
) {
    /** All speech-like labels together. */
    val speech: KindScore get() = KindScore(speechByKind.values.sumOf { it.labels }, speechByKind.values.sumOf { it.found })

    companion object {
        /** The label kinds of the test nights in which something is said. */
        val SPEECH_LIKE = listOf("speech", "speech_quiet", "whisper", "sleeptalk", "sleeptalk_real")

        /**
         * Scores [events] against [labels] of a night of [length] seconds. An event and a label
         * match when they overlap or lie at most [tolerance] seconds apart. Cut playback plays each
         * event from [leadIn] before it to [tail] after it, within the night, and stretches that
         * overlap are played once.
         */
        fun of(labels: List<Label>, events: List<Span>, length: Double, leadIn: Double, tail: Double, tolerance: Double = 1.0): NightScore {
            fun matches(event: Span, label: Label) =
                event.start <= label.span.end + tolerance && label.span.start <= event.end + tolerance

            val falseEvents = events.count { event -> labels.none { matches(event, it) } }
            val speechByKind = SPEECH_LIKE.mapNotNull { kind ->
                val ofKind = labels.filter { it.kind == kind }
                if (ofKind.isEmpty()) return@mapNotNull null
                kind to KindScore(ofKind.size, ofKind.count { label -> events.any { matches(it, label) } })
            }.toMap()

            return NightScore(events.size, falseEvents, speechByKind, cutSeconds(events, length, leadIn, tail))
        }

        private fun cutSeconds(events: List<Span>, length: Double, leadIn: Double, tail: Double): Double {
            var total = 0.0
            var playedUntil = 0.0
            for (event in events.sortedBy { it.start }) {
                val from = maxOf(event.start - leadIn, playedUntil)
                val until = minOf(event.end + tail, length)
                if (until > from) total += until - from
                playedUntil = maxOf(playedUntil, until)
            }
            return total
        }
    }
}
