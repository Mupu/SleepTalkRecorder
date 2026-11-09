package me.mupu.sleeptalkrecorder.ui.format

import android.content.res.Resources
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.time.SleepDate
import me.mupu.sleeptalkrecorder.time.SleepKind
import java.text.NumberFormat
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A position in a recording as hours, minutes and whole seconds: "6:10:00". */
fun formatPosition(position: Duration): String {
    val seconds = position.seconds.coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60)
}

/** The name of a sleep: "Night of 8 to 9 November" or "Nap on 9 November". */
fun sleepTitle(resources: Resources, date: SleepDate): String {
    val locale = resources.configuration.locales[0]
    val dayMonth = DateTimeFormatter.ofPattern(resources.getString(R.string.date_pattern_day_month), locale)

    return when (date.kind) {
        SleepKind.NAP -> resources.getString(R.string.nap_title, date.date.format(dayMonth))
        SleepKind.NIGHT -> {
            val morning = date.date.plusDays(1)
            // "8 to 9 November", but "31 October to 1 November".
            val evening = if (morning.month == date.date.month) {
                date.date.format(DateTimeFormatter.ofPattern(resources.getString(R.string.date_pattern_day), locale))
            } else {
                date.date.format(dayMonth)
            }
            resources.getString(R.string.night_title, evening, morning.format(dayMonth))
        }
    }
}

/** The date above a group of sleeps in the library: "Sunday, 8 November". */
fun groupDate(resources: Resources, date: LocalDate): String {
    val locale = resources.configuration.locales[0]
    return date.format(DateTimeFormatter.ofPattern(resources.getString(R.string.date_pattern_group), locale))
}

/**
 * The length of an event, as precise as it matters: "0.4 s" (with the app language's decimal mark)
 * below ten seconds, "12 s" below a minute, then "2 min 05 s", and from an hour on as
 * [formatDuration].
 */
fun formatEventLength(resources: Resources, length: Duration): String {
    val millis = length.toMillis().coerceAtLeast(0)
    return when {
        millis < 10_000 -> {
            val number = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply {
                minimumFractionDigits = 1
                maximumFractionDigits = 1
            }
            resources.getString(R.string.duration_seconds_decimal, number.format(millis / 1_000.0))
        }
        millis < 60_000 -> resources.getString(R.string.duration_seconds, (millis / 1_000).toInt())
        millis < 3_600_000 -> resources.getString(R.string.duration_minutes_seconds, (millis / 60_000).toInt(), (millis / 1_000 % 60).toInt())
        else -> formatDuration(resources, length)
    }
}

/** A length of time in hours and minutes: "7 h 31 min", "12 min", or "25 s" below a minute. */
fun formatDuration(resources: Resources, duration: Duration): String {
    val minutes = duration.toMinutes().coerceAtLeast(0)
    return if (minutes < 1) {
        resources.getString(R.string.duration_seconds, duration.seconds.coerceAtLeast(0).toInt())
    } else if (minutes < 60) {
        resources.getString(R.string.duration_minutes, minutes.toInt())
    } else {
        resources.getString(R.string.duration_hours_minutes, (minutes / 60).toInt(), (minutes % 60).toInt())
    }
}
