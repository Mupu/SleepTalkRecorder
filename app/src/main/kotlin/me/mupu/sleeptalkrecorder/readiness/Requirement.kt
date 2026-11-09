package me.mupu.sleeptalkrecorder.readiness

/**
 * What the phone has to allow so that a whole night can be recorded. Unlike a [VendorStep], each
 * can be checked, so the home screen warns while one is missing.
 */
enum class Requirement {
    /** The microphone permission; without it nothing can be recorded. */
    MICROPHONE,

    /** Notifications, which show the running recording and its Stop button. */
    NOTIFICATIONS,

    /** The exemption from battery optimisation, so that Android does not end the recording at night. */
    BATTERY_OPTIMISATION,
}
