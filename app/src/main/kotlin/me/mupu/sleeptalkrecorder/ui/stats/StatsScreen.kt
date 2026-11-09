package me.mupu.sleeptalkrecorder.ui.stats

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.time.SleepKind
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import me.mupu.sleeptalkrecorder.ui.format.formatEventLength
import me.mupu.sleeptalkrecorder.ui.format.sleepTitle
import java.text.NumberFormat
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The numbers of a sleep and of all nights in tiles and bar charts: opened from a sleep it shows
 * that sleep first and switches to all sleeps, opened from the library only all sleeps.
 */
@Composable
fun StatsScreen(viewModel: StatsViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(topBar = { AppBar(stringResource(R.string.stats), navigationIcon = { BackIcon(onBack) }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (state.hasSleep) {
                ScopeSwitch(state.scope, onScopeChange = viewModel::setScope)
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
            ) {
                when (state.scope) {
                    StatsScope.THIS_SLEEP -> ThisSleep(state)
                    StatsScope.ALL_SLEEPS -> state.nightStats?.let { AllNights(it, highlightSleepId = state.sleep?.id) }
                }
            }
        }
    }
}

@Composable
private fun ScopeSwitch(scope: StatsScope, onScopeChange: (StatsScope) -> Unit) {
    val labels = listOf(StatsScope.THIS_SLEEP to R.string.stats_this_sleep, StatsScope.ALL_SLEEPS to R.string.stats_all_sleeps)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        labels.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = scope == value,
                onClick = { onScopeChange(value) },
                shape = SegmentedButtonDefaults.itemShape(index, labels.size),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

@Composable
private fun ColumnScope.ThisSleep(state: StatsUiState) {
    val resources = LocalResources.current
    val clock = LocalClockFormatter.current
    val zone = ZoneId.systemDefault()
    val sleep = state.sleep ?: return
    val date = sleep.date
    Caption(sleepTitle(resources, date))

    val stats = state.sleepStats
    if (stats == null) {
        Hint(stringResource(R.string.stats_sleep_waiting))
        return
    }

    val busiest = stats.busiest
    Tiles(
        stats.events.toString() to stringResource(R.string.stats_events),
        formatEventLength(resources, stats.talk) to stringResource(R.string.stats_talked),
        percent(stats.speechShare) to stringResource(R.string.stats_speech_share),
        (busiest?.let { stringResource(R.string.time_range, clock.format(it.start, zone), clock.format(it.start.plus(Duration.ofMinutes(10)), zone)) } ?: NONE)
            to stringResource(R.string.stats_busiest_ten_minutes),
    )

    val bins = stats.tenMinutes
    val hours = bins.indices.filter { bins[it].start.atZone(zone).minute == 0 }.ifEmpty { listOf(0) }
    val labelled = hours.filterIndexed { index, _ -> index % everyNth(hours.size) == 0 }.toSet()
    ChartCard(stringResource(R.string.stats_per_ten_minutes)) {
        BarChart(
            bars = bins.mapIndexed { index, bin ->
                Bar(
                    events = (bin.events - bin.speech).toFloat(),
                    speech = bin.speech.toFloat(),
                    label = if (index in labelled) clock.format(bin.start, zone) else null,
                )
            },
            highlight = busiest?.let(bins::indexOf),
            maxLabel = { it.toInt().toString() },
        )
        Legend()
    }

    ChartCard(null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(104.dp)) {
                Donut(stats.speechShare ?: 0f, Modifier.fillMaxSize())
                Text(percent(stats.speechShare), style = MaterialTheme.typography.titleMedium)
            }
            Column {
                CardTitle(stringResource(R.string.stats_share_title))
                Hint(pluralStringResource(R.plurals.stats_share_text, stats.events, stats.speechEvents, stats.events))
            }
        }
    }

    val nap = stats.kind == SleepKind.NAP
    ChartCard(stringResource(R.string.stats_compare_title)) {
        val average = stats.averageTalk
        if (average != null) {
            BarChart(
                bars = listOf(
                    Bar(speech = minutes(stats.talk), label = stringResource(R.string.stats_compare_this)),
                    Bar(
                        speech = minutes(average),
                        label = stringResource(if (nap) R.string.stats_compare_average_nap else R.string.stats_compare_average_night),
                    ),
                ),
                highlight = null,
                maxLabel = { formatEventLength(resources, Duration.ofSeconds((it * 60).toLong())) },
                atLeast = ONE_SECOND,
                height = 110.dp,
            )
            Hint(pluralStringResource(if (nap) R.plurals.stats_compare_naps else R.plurals.stats_compare_nights, stats.compared, stats.compared))
        } else {
            Hint(stringResource(if (nap) R.string.stats_compare_no_nap else R.string.stats_compare_no_night))
        }
    }
}

@Composable
private fun ColumnScope.AllNights(stats: NightStats, highlightSleepId: Long?) {
    val resources = LocalResources.current
    val clock = LocalClockFormatter.current
    val since = stats.since
    if (since == null) {
        Hint(stringResource(R.string.stats_no_nights))
        return
    }
    val dayMonth = DateTimeFormatter.ofPattern(stringResource(R.string.date_pattern_day_month), resources.configuration.locales[0])
    Caption(
        pluralStringResource(R.plurals.stats_nights_since, stats.nights, stats.nights, since.format(dayMonth)) + " · " +
            stringResource(R.string.stats_naps_left_out),
    )

    val decimal = NumberFormat.getNumberInstance(resources.configuration.locales[0]).apply { maximumFractionDigits = 1 }
    val busiest = stats.busiestHour
    Tiles(
        decimal.format(stats.averageEvents) to stringResource(R.string.stats_average_events),
        formatEventLength(resources, stats.averageTalk) to stringResource(R.string.stats_average_talked),
        percent(stats.speechShare) to stringResource(R.string.stats_speech_share),
        (busiest?.let { stringResource(R.string.time_range, clock.format(LocalTime.of(it, 0)), clock.format(LocalTime.of((it + 1) % 24, 0))) } ?: NONE)
            to stringResource(R.string.stats_busiest_hour),
    )

    val day = DateTimeFormatter.ofPattern(stringResource(R.string.date_pattern_day), resources.configuration.locales[0])
    val nights = stats.talkPerNight
    ChartCard(stringResource(R.string.stats_talk_per_night)) {
        BarChart(
            bars = nights.mapIndexed { index, night ->
                Bar(speech = minutes(night.talk), label = night.date.format(day).takeIf { index % everyNth(nights.size, 7) == 0 })
            },
            highlight = nights.indexOfFirst { it.sleepId == highlightSleepId }.takeIf { it >= 0 },
            maxLabel = { formatEventLength(resources, Duration.ofSeconds((it * 60).toLong())) },
                atLeast = ONE_SECOND,
        )
    }

    val hours = stats.perHour
    ChartCard(stringResource(R.string.stats_per_hour)) {
        BarChart(
            bars = hours.mapIndexed { index, hour ->
                Bar(
                    events = hour.averageEvents.toFloat(),
                    label = clock.format(LocalTime.of(hour.hour, 0)).takeIf { index % everyNth(hours.size) == 0 },
                )
            },
            highlight = busiest?.let { hour -> hours.indexOfFirst { it.hour == hour } },
            maxLabel = { decimal.format(it) },
        )
        Hint(stringResource(R.string.stats_per_hour_text))
    }
}

/** Four numbers in two rows of two tiles, each a value with its label below. */
@Composable
private fun Tiles(vararg tiles: Pair<String, String>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.toList().chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((value, label) in row) {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerLow,
                        modifier = Modifier.weight(1f),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Text(value, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
                            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartCard(title: String?, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            title?.let { CardTitle(it) }
            content()
        }
    }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/** What the numbers below count. */
@Composable
private fun Caption(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Start)
}

/** The colours of plain and speech events, as on the sleep screen. */
@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(MaterialTheme.colorScheme.primary, stringResource(R.string.kind_event))
        LegendItem(MaterialTheme.colorScheme.tertiary, stringResource(R.string.kind_speech))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = color, shape = RoundedCornerShape(2.dp), modifier = Modifier.size(10.dp)) {}
        Spacer(Modifier.size(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A share from 0 to 1 as a percentage in the app language, "50 %"; a dash without one. */
@Composable
private fun percent(share: Float?): String {
    share ?: return NONE
    return NumberFormat.getPercentInstance(LocalResources.current.configuration.locales[0]).format(share.toDouble())
}

/** A length in minutes, as the charts of talk draw it. */
private fun minutes(duration: Duration): Float = duration.toMillis() / 60_000f

/** The least a chart of talk in minutes stands for, so that a few seconds of talk still show. */
private const val ONE_SECOND = 1f / 60

/** Which labels of [count] are drawn so that at most [most] of them are: every first, second, third... */
private fun everyNth(count: Int, most: Int = 6): Int = maxOf(1, (count + most - 1) / most)

/** What a tile shows when there is nothing to count. */
private const val NONE = "–"
