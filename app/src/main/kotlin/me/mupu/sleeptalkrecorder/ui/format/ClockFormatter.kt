package me.mupu.sleeptalkrecorder.ui.format

import androidx.compose.runtime.staticCompositionLocalOf
import me.mupu.sleeptalkrecorder.settings.TimeFormat
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Writes clock times in the user's [TimeFormat]: "23:41" or "11:41 PM", with seconds "23:41:07" or
 * "11:41:07 PM". Every clock time the app
 * shows goes through it, so the setting applies everywhere and ignores the phone's own format
 * and the app language.
 */
class ClockFormatter(format: TimeFormat) {

    /** Whether the user reads 24 hours; time pickers are opened in the same format. */
    val is24Hour: Boolean = format == TimeFormat.HOURS_24

    // AM and PM are written the same way in every app language.
    private val minutes = pattern("mm")
    private val seconds = pattern("mm:ss")
    private val wholeHours = DateTimeFormatter.ofPattern("h a", Locale.US)

    /** The clock time of [instant] in [zone], by default the device's current zone. */
    fun format(instant: Instant, zone: ZoneId = ZoneId.systemDefault(), seconds: Boolean = false): String =
        instant.atZone(zone).format(if (seconds) this.seconds else minutes)

    /** A clock time without a day, such as a setting: "07:30" or "7:30 AM". */
    fun format(time: LocalTime): String = time.format(minutes)

    /**
     * A full [hour] from 0 to 24 as a scale label: "06:00" or the shorter "6 AM". Hour 24 is the
     * midnight at the end of a day, "24:00" or "12 AM".
     */
    fun formatHour(hour: Int): String = when {
        is24Hour -> "%02d:00".format(Locale.US, hour)
        else -> LocalTime.of(hour % 24, 0).format(wholeHours)
    }

    private fun pattern(afterHours: String) =
        DateTimeFormatter.ofPattern(if (is24Hour) "HH:$afterHours" else "h:$afterHours a", Locale.US)
}

/** The [ClockFormatter] for the user's time format; the app's root provides it from the settings. */
val LocalClockFormatter = staticCompositionLocalOf { ClockFormatter(TimeFormat.HOURS_24) }
