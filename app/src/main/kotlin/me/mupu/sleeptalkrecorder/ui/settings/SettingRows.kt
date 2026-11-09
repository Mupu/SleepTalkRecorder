package me.mupu.sleeptalkrecorder.ui.settings

import android.app.TimePickerDialog
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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
private val HintedControlPadding = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 4.dp)

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
    hint: String? = null,
) {
    Column {
        SettingLabel(icon, label)
        SingleChoiceSegmentedButtonRow((if (hint == null) ControlPadding else HintedControlPadding).fillMaxWidth()) {
            val crowded = options.size > MAX_OPTIONS_WITH_CHECK
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    // Four or more labels leave no room for the check mark and the usual padding on
                    // a phone; the fill alone marks the choice then.
                    icon = if (crowded) ({}) else ({ SegmentedButtonDefaults.Icon(option == selected) }),
                    contentPadding = if (crowded) CrowdedPadding else SegmentedButtonDefaults.ContentPadding,
                ) {
                    Text(optionLabel(option), maxLines = 1)
                }
            }
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = ControlPadding,
            )
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
 * An on/off setting with its switch at the end of the row. The whole row switches, so it is one
 * target that screen readers announce as a switch. [content] is shown below the row, for example
 * the value the setting works with while it is on.
 */
@Composable
internal fun SwitchSetting(
    @DrawableRes icon: Int,
    label: String,
    hint: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    content: @Composable () -> Unit = {},
) {
    Column {
        Box(Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)) {
            SettingLabel(icon, label, hint) {
                Switch(checked = checked, onCheckedChange = null)
            }
        }
        content()
    }
}

/** A row that opens another screen, with a chevron at its end. */
@Composable
internal fun LinkSetting(@DrawableRes icon: Int, label: String, hint: String, onClick: () -> Unit) {
    Box(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        SettingLabel(icon, label, hint) {
            Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null)
        }
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

    SwitchSetting(icon, label, hint, checked = enabled, onCheckedChange = onEnabledChange) {
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
internal fun TimePicker(time: LocalTime, onPicked: (LocalTime) -> Unit, onClose: () -> Unit) {
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

/**
 * A number of whole days within [range], shown as a button with [valueLabel] that opens a dialog
 * to type it. The value is stored only when the dialog is confirmed, never while typing, so a
 * half-typed number never takes effect.
 */
@Composable
internal fun DaysField(
    value: Duration,
    range: ClosedRange<Duration>,
    valueLabel: String,
    dialogTitle: String,
    onChange: (Duration) -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(false) }

    OutlinedButton(onClick = { editing = true }, modifier = ControlPadding) {
        Text(valueLabel)
    }
    if (editing) {
        DaysDialog(
            initial = value,
            range = range,
            title = dialogTitle,
            onConfirm = {
                editing = false
                onChange(it)
            },
            onDismiss = { editing = false },
        )
    }
}

@Composable
private fun DaysDialog(
    initial: Duration,
    range: ClosedRange<Duration>,
    title: String,
    onConfirm: (Duration) -> Unit,
    onDismiss: () -> Unit,
) {
    val fewest = range.start.toDays()
    val most = range.endInclusive.toDays()
    var text by rememberSaveable { mutableStateOf(initial.toDays().toString()) }
    val days = text.toLongOrNull()?.takeIf { it in fewest..most }
    val confirm = { days?.let { onConfirm(Duration.ofDays(it)) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                // Digits only; the number keyboard also offers signs and separators.
                onValueChange = { text = it.filter(Char::isDigit).take(most.toString().length) },
                singleLine = true,
                isError = days == null,
                suffix = { Text(stringResource(R.string.settings_auto_delete_days_unit)) },
                supportingText = { Text(stringResource(R.string.settings_auto_delete_days_range, fewest, most)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { confirm() }),
            )
        },
        confirmButton = {
            TextButton(onClick = { confirm() }, enabled = days != null) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
internal fun SettingLabel(
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

/** The most options a [ChoiceSetting] shows with a check mark on the chosen one. */
private const val MAX_OPTIONS_WITH_CHECK = 3

/** The padding of a segment in a [ChoiceSetting] with more than [MAX_OPTIONS_WITH_CHECK] options. */
private val CrowdedPadding = PaddingValues(horizontal = 4.dp)
