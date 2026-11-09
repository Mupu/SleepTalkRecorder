package me.mupu.sleeptalkrecorder.detection

import kotlin.math.roundToInt

/**
 * The loudness features of a stretch of audio, one record per hop of [HOP_SAMPLES] samples
 * (20 ms at 16 kHz), so hop `i` covers samples `i * 320` until `(i + 1) * 320` of its recording.
 * Finding events reads only this track, never the audio.
 *
 * Each feature is a column of one unsigned byte per hop, which keeps a night of 8 hours at
 * 11.5 MB and lets the detector scan a column without touching the others. Levels are dBFS in
 * steps of 0.5 dB from [SILENT_DB] (code 0, also digital silence) to 0 dBFS (code 255).
 */
class FeatureTrack(
    /** Full-band RMS level. */
    val rms: ByteArray,
    /** Level of the largest sample. */
    val peak: ByteArray,
    /** Level of the band below 300 Hz: rumble, traffic, hum. */
    val low: ByteArray,
    /** Level of the speech band, 300 to 3400 Hz: the signal the baseline and events come from. */
    val speech: ByteArray,
    /** Level of the band above 3400 Hz: hiss, sibilants, clicks. */
    val high: ByteArray,
    /** Spectral flatness of the speech band, 0 (tonal) to 255 (white noise). */
    val flatness: ByteArray,
    /** Zero crossings within the hop, saturating at 255. */
    val zeroCrossings: ByteArray,
    /** Voicing, the largest normalised autocorrelation for pitches of 70 to 400 Hz, 0 to 255. */
    val voicing: ByteArray,
) {

    /** The number of hops. */
    val size: Int = rms.size

    init {
        require(listOf(peak, low, speech, high, flatness, zeroCrossings, voicing).all { it.size == size }) {
            "every column needs one value per hop"
        }
    }

    /** The first [hops] hops of this track. */
    fun take(hops: Int): FeatureTrack {
        if (hops >= size) return this
        val n = hops.coerceAtLeast(0)
        return FeatureTrack(
            rms.copyOf(n), peak.copyOf(n), low.copyOf(n), speech.copyOf(n),
            high.copyOf(n), flatness.copyOf(n), zeroCrossings.copyOf(n), voicing.copyOf(n),
        )
    }

    companion object {
        /** Samples per hop: 20 ms at 16 kHz. */
        const val HOP_SAMPLES = 320

        /** The length of a hop at the recording rate of 16 kHz, in milliseconds. */
        const val HOP_MILLIS = 20L

        /** Bytes of one hop's record: one per feature. */
        const val RECORD_BYTES = 8

        /** The level of code 0: digital silence or anything at most this loud, in dBFS. */
        const val SILENT_DB = -127.5f

        /** The level of a level code in dBFS. */
        fun levelDb(code: Byte): Float = unsigned(code) / 2f + SILENT_DB

        /** The code of a level in dBFS, rounded to 0.5 dB and held within the codes. */
        fun levelCode(db: Double): Byte = ((db.coerceIn(SILENT_DB.toDouble(), 0.0) - SILENT_DB) * 2).roundToInt().toByte()

        /** The track of [records], each [RECORD_BYTES] in the order of the columns; a partial record at the end is left out. */
        fun fromRecords(records: ByteArray, offset: Int = 0, hops: Int = (records.size - offset) / RECORD_BYTES): FeatureTrack {
            val columns = Array(RECORD_BYTES) { ByteArray(hops) }
            for (hop in 0 until hops) {
                val at = offset + hop * RECORD_BYTES
                for (feature in 0 until RECORD_BYTES) columns[feature][hop] = records[at + feature]
            }
            return FeatureTrack(columns[0], columns[1], columns[2], columns[3], columns[4], columns[5], columns[6], columns[7])
        }

        /** The tracks of several recordings back to back. */
        fun concat(tracks: List<FeatureTrack>): FeatureTrack {
            if (tracks.size == 1) return tracks.single()
            fun column(of: (FeatureTrack) -> ByteArray): ByteArray {
                val joined = ByteArray(tracks.sumOf { it.size })
                var at = 0
                for (track in tracks) {
                    of(track).copyInto(joined, at)
                    at += track.size
                }
                return joined
            }
            return FeatureTrack(
                column { it.rms }, column { it.peak }, column { it.low }, column { it.speech },
                column { it.high }, column { it.flatness }, column { it.zeroCrossings }, column { it.voicing },
            )
        }

        internal fun unsigned(code: Byte): Int = code.toInt() and 0xFF
    }
}
