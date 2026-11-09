package me.mupu.sleeptalkrecorder.recording

import java.time.Instant

/** The two system clocks a recording is anchored with. */
interface Clocks {

    /** The wall clock, which can jump when the time is set. */
    fun now(): Instant

    /** Nanoseconds since boot, including deep sleep; never jumps. */
    fun elapsedNanos(): Long
}
