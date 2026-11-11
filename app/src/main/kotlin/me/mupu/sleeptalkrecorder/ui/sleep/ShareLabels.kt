package me.mupu.sleeptalkrecorder.ui.sleep

import android.content.Context
import android.content.res.Resources
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.ui.graphics.toArgb
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.data.SleepEvent
import me.mupu.sleeptalkrecorder.data.SleepSummary
import me.mupu.sleeptalkrecorder.playback.SleepTimeline
import me.mupu.sleeptalkrecorder.share.CardColors
import me.mupu.sleeptalkrecorder.share.EventLabels
import me.mupu.sleeptalkrecorder.ui.format.ClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatEventLength
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * What the card and the caption of a shared [event] of [sleep] say, in the app language of
 * [resources] and the user's time format: "8 Nov, 22:55:41", the transcript in quotation marks.
 */
fun shareLabels(resources: Resources, clock: ClockFormatter, timeline: SleepTimeline, sleep: SleepSummary?, event: SleepEvent): EventLabels {
    val zone = ZoneId.systemDefault()
    val start = timeline.instantAt(event.start)
    val date = DateTimeFormatter.ofPattern(resources.getString(R.string.date_pattern_share), resources.configuration.locales[0])
    return EventLabels(
        name = event.name?.takeIf { it.isNotBlank() },
        kind = kindName(resources, event.kindId, event.kindName),
        time = resources.getString(R.string.share_time, start.atZone(zone).format(date), clock.format(start, zone, seconds = true)),
        transcript = event.transcript?.takeIf { event.isSpeech && it.isNotBlank() }?.let { resources.getString(R.string.transcript_quoted, it) },
        sleep = sleep?.let { sleepTitle(resources, it.date) }.orEmpty(),
        length = formatEventLength(resources, event.end - event.start),
    )
}

/** The colours of a shared event's card: the app's dark theme, whichever the app shows. */
fun cardColors(context: Context): CardColors {
    val dark = dynamicDarkColorScheme(context)
    return CardColors(dark.surfaceContainer.toArgb(), dark.primary.toArgb(), dark.onSurface.toArgb(), dark.tertiary.toArgb())
}
