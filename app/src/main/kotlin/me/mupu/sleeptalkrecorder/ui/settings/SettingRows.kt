package me.mupu.sleeptalkrecorder.ui.settings

import android.app.TimePickerDialog
import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.ui.format.LocalClockFormatter
import java.time.Duration
import java.time.LocalTime
import kotlin.math.roundToLong

// Settings rows follow one layout: a choice between several options sits below its label, and an
// on/off switch sits at the right end of its row. Controls below a label start where the label
// text starts, right of the icon column.
private val ControlPadding = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp)

/** The heading of a group of settings. */
@Composable
internal fun SettingsSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 56.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

/** A setting with a few [options] shown as segmented buttons below its [label]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T> ChoiceSetting(
    @DrawableRes icon: Int,
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column {
        SettingLabel(icon, label)
        SingleChoiceSegmentedButtonRow(ControlPadding.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                ) {
                    Text(optionLabel(option), maxLines = 1)
                }
            }
        }
    }
}

/**
 * A length of time between [range]'s ends in steps of [step], set with a slider below its label.
 * The value is stored once the slider is let go, not on every step it passes.
 */
@Composable
internal fun DurationSetting(
    @DrawableRes icon: Int,
    label: String,
    hint: String,
    value: Duration,
    range: ClosedRange<Duration>,
    onChange: (Duration) -> Unit,
    step: Duration = Duration.ofMillis(100),
) {
    // The slider works in milliseconds; it keeps its own position while dragged.
    var dragged by remember(value) { mutableFloatStateOf(value.toMillis().toFloat()) }
    val start = range.start.toMillis()
    val end = range.endInclusive.toMillis()

    Column {
        SettingLabel(icon, label, hint) {
            Text(
                stringResource(R.string.seconds_decimal, dragged / 1000f),
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Slider(
            value = dragged,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onChange(Duration.ofMillis(dragged.roundToLong())) },
            valueRange = start.toFloat()..end.toFloat(),
            // Steps are the stops between both ends.
            steps = ((end - start) / step.toMillis() - 1).toInt(),
            modifier = ControlPadding,
        )
    }
}

/**
 * A clock time that can be switched off: a switch at the end of the row and, while it is on, a
 * button below the label that opens Android's time picker in the user's time format.
 */
@Composable
internal fun SwitchableTimeSetting(
    @DrawableRes icon: Int,
    label: String,
    hint: String,
    enabled: Boolean,
    time: LocalTime,
    onEnabledChange: (Boolean) -> Unit,
    onTimeChange: (LocalTime) -> Unit,
) {
    var picking by rememberSaveable { mutableStateOf(false) }

    Column {
        // The whole row switches, so it is one target that screen readers announce as a switch.
        Box(Modifier.toggleable(value = enabled, role = Role.Switch, onValueChange = onEnabledChange)) {
            SettingLabel(icon, label, hint) {
                Switch(checked = enabled, onCheckedChange = null)
            }
        }
        if (enabled) {
            OutlinedButton(onClick = { picking = true }, modifier = ControlPadding) {
                Text(LocalClockFormatter.current.format(time))
            }
        }
    }

    if (picking) {
        TimePicker(time, onPicked = onTimeChange, onClose = { picking = false })
    }
}

/**
 * Shows Android's time picker dialog while in the composition. The dialog is a platform view, so
 * it does not see the Compose theme: it is given the light or dark dialog theme of the colors the
 * app draws with, and the 12- or 24-hour clock of the user's time format.
 */
@Composable
private fun TimePicker(time: LocalTime, onPicked: (LocalTime) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val is24Hour = LocalClockFormatter.current.is24Hour
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val currentOnPicked by rememberUpdatedState(onPicked)
    val currentOnClose by rememberUpdatedState(onClose)

    DisposableEffect(context, is24Hour, dark) {
        val theme = if (dark) {
            android.R.style.Theme_DeviceDefault_Dialog_Alert
        } else {
            android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        }
        val dialog = TimePickerDialog(
            context,
            theme,
            { _, hour, minute -> currentOnPicked(LocalTime.of(hour, minute)) },
            time.hour,
            time.minute,
            is24Hour,
        )
        dialog.setOnDismissListener { currentOnClose() }
        dialog.show()

        // Leaving the composition, for example on rotation, closes the dialog without counting
        // as a dismissal, so it opens again afterwards.
        onDispose {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
        }
    }
}

@Composable
private fun SettingLabel(
    @DrawableRes icon: Int,
    label: String,
    hint: String? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = hint?.let { { Text(it) } },
        leadingContent = { Icon(painterResource(icon), contentDescription = null) },
        trailingContent = trailing,
    )
}
