package me.mupu.sleeptalkrecorder.data

import me.mupu.sleeptalkrecorder.detection.EventDetector
import me.mupu.sleeptalkrecorder.detection.FeatureTrackFile

/** The producers of the annotations the app makes itself, besides those of an [me.mupu.sleeptalkrecorder.transcription.EventAnalysis]. */
internal object Producers {
    /** The producer of the annotations the recording itself makes. */
    val RECORDER = Producer("recorder")

    /**
     * The producer of the detector's annotations; its version changes with the detector's rules
     * and with the feature format, so either change finds events again.
     */
    val DETECTOR = Producer("loudness", "${EventDetector.VERSION}/${FeatureTrackFile.VERSION}")
}
