package me.mupu.sleeptalkrecorder.ui.setup

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.readiness.PermissionPrompt
import me.mupu.sleeptalkrecorder.readiness.PhoneReadiness
import me.mupu.sleeptalkrecorder.readiness.Requirement
import me.mupu.sleeptalkrecorder.readiness.SystemPage
import me.mupu.sleeptalkrecorder.readiness.VendorStep
import me.mupu.sleeptalkrecorder.readiness.openFirst
import me.mupu.sleeptalkrecorder.settings.Settings
import me.mupu.sleeptalkrecorder.settings.SettingsStore
import me.mupu.sleeptalkrecorder.ui.components.AppBar
import me.mupu.sleeptalkrecorder.ui.components.BackIcon
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the permissions page shows. */
data class PermissionsUiState(
    val missing: Set<Requirement>,
    /** Whether the app asked for the microphone before; see [PermissionPrompt]. */
    val microphoneAsked: Boolean = false,
    /** Whether the app asked for notifications before; see [PermissionPrompt]. */
    val notificationsAsked: Boolean = false,
)

/** State and actions of the permissions page, in the setup and when opened from home. */
class PermissionsViewModel(
    private val store: SettingsStore,
    private val readiness: PhoneReadiness,
) : ViewModel() {

    /** The steps of this phone's vendor; they cannot be checked, so they are only offered. */
    val vendorSteps: List<VendorStep> = readiness.vendorSteps

    val state: StateFlow<PermissionsUiState> = combine(
        readiness.missing,
        store.flow(Settings.microphoneAsked),
        store.flow(Settings.notificationsAsked),
        ::PermissionsUiState,
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PermissionsUiState(readiness.missing.value))

    /** The system dialog for [requirement]'s permission was answered. */
    fun permissionAnswered(requirement: Requirement) {
        readiness.refresh()
        val asked = when (requirement) {
            Requirement.MICROPHONE -> Settings.microphoneAsked
            Requirement.NOTIFICATIONS -> Settings.notificationsAsked
            Requirement.BATTERY_OPTIMISATION -> return
        }
        viewModelScope.launch { store.set(asked, true) }
    }

    /** Finishes this setup step. */
    fun finishSetupStep() {
        viewModelScope.launch { SetupStep.PERMISSIONS.finish(store) }
    }

    /** Goes back to the setup step before this one. */
    fun backSetupStep() {
        viewModelScope.launch { SetupStep.PERMISSIONS.back(store) }
    }
}

/** Where the permissions page is shown, which decides how it is left. */
sealed interface PermissionsPlace {
    /** As a step of the first-start setup, with a Back button to the step before. */
    data class Setup(val step: SetupStep) : PermissionsPlace

    /** Opened from the home screen's warning, with a back arrow. */
    data object FromHome : PermissionsPlace
}

/**
 * Microphone, notifications and battery optimisation, then the steps of the phone's vendor. Every
 * button opens the system dialog, or the settings page where the dialog is no longer offered.
 * [onDone] needs the microphone, without which the app cannot record.
 */
@Composable
fun PermissionsScreen(
    viewModel: PermissionsViewModel,
    place: PermissionsPlace,
    onBack: () -> Unit,
    onDone: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current ?: return

    val microphoneDialog = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.permissionAnswered(Requirement.MICROPHONE)
    }
    val notificationDialog = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.permissionAnswered(Requirement.NOTIFICATIONS)
    }
    val onAllow: (Requirement) -> Unit = { requirement ->
        when (requirement) {
            Requirement.MICROPHONE -> ask(activity, Manifest.permission.RECORD_AUDIO, state.microphoneAsked) {
                microphoneDialog.launch(Manifest.permission.RECORD_AUDIO)
            }
            Requirement.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                ask(activity, Manifest.permission.POST_NOTIFICATIONS, state.notificationsAsked) {
                    notificationDialog.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                // The permission is granted (or not needed before Android 13), so the user turned
                // notifications off in the app's notification settings.
                activity.openFirst(listOf(SystemPage.APP_NOTIFICATIONS, SystemPage.APP_DETAILS))
            }
            Requirement.BATTERY_OPTIMISATION -> activity.openFirst(
                listOf(SystemPage.BATTERY_EXEMPTION, SystemPage.BATTERY_EXEMPTION_LIST, SystemPage.APP_DETAILS),
            )
        }
    }

    // Which page each vendor step opened, while this screen is shown; whether the setting took
    // effect cannot be read back.
    val openedVendorPages = remember { mutableStateMapOf<VendorStep, SystemPage?>() }

    Scaffold(
        topBar = {
            if (place == PermissionsPlace.FromHome) {
                AppBar(title = stringResource(R.string.setup_permissions_title), navigationIcon = { BackIcon(onBack) })
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (place is PermissionsPlace.Setup) {
                StepLabel(place.step)
                Text(stringResource(R.string.setup_permissions_title), style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                stringResource(R.string.setup_permissions_text),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Requirement.entries.forEach { requirement ->
                RequirementRow(requirement, met = requirement !in state.missing, onAllow = { onAllow(requirement) })
            }
            viewModel.vendorSteps.forEach { step ->
                val opened = step in openedVendorPages
                VendorStepRow(
                    step = step,
                    opened = opened,
                    fellBack = opened && openedVendorPages[step] != step.pages.first(),
                    onOpen = { openedVendorPages[step] = activity.openFirst(step.pages) },
                )
            }

            Text(
                stringResource(R.string.setup_recheck),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (place is PermissionsPlace.Setup) {
                    TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
                }
                Spacer(Modifier.weight(1f))
                Button(onClick = onDone, enabled = Requirement.MICROPHONE !in state.missing) {
                    Text(stringResource(if (place is PermissionsPlace.Setup) R.string.next else R.string.done))
                }
            }
        }
    }
}

/** Where [step] stands in the setup: "2 / 2". */
@Composable
internal fun StepLabel(step: SetupStep) {
    Text(
        stringResource(R.string.setup_step, step.ordinal + 1, SetupStep.entries.size),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Opens [permission]'s system dialog with [launchDialog], or the app's settings where Android no longer shows it. */
private fun ask(activity: Activity, permission: String, askedBefore: Boolean, launchDialog: () -> Unit) {
    when (PermissionPrompt.of(askedBefore, activity.shouldShowRequestPermissionRationale(permission))) {
        PermissionPrompt.DIALOG -> launchDialog()
        PermissionPrompt.SETTINGS -> activity.openFirst(listOf(SystemPage.APP_DETAILS))
    }
}

@Composable
private fun RequirementRow(requirement: Requirement, met: Boolean, onAllow: () -> Unit) {
    val (icon, title, why, button) = when (requirement) {
        Requirement.MICROPHONE -> RowTexts(R.drawable.ic_mic, R.string.requirement_microphone, R.string.requirement_microphone_why, R.string.allow)
        Requirement.NOTIFICATIONS ->
            RowTexts(R.drawable.ic_notifications, R.string.requirement_notifications, R.string.requirement_notifications_why, R.string.allow)
        Requirement.BATTERY_OPTIMISATION ->
            RowTexts(R.drawable.ic_battery, R.string.requirement_battery, R.string.requirement_battery_why, R.string.allow_exemption)
    }
    SetupRow(
        icon = if (met) R.drawable.ic_check_circle else icon,
        done = met,
        title = stringResource(title),
        required = requirement == Requirement.MICROPHONE,
        text = stringResource(why),
    ) {
        if (met) {
            Text(
                stringResource(R.string.granted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            FilledTonalButton(onClick = onAllow) { Text(stringResource(button)) }
        }
    }
}

@Composable
private fun VendorStepRow(step: VendorStep, opened: Boolean, fellBack: Boolean, onOpen: () -> Unit) {
    val (title, hint) = when (step) {
        VendorStep.SAMSUNG_NEVER_SLEEPING -> R.string.vendor_samsung to R.string.vendor_samsung_hint
        VendorStep.XIAOMI_AUTOSTART -> R.string.vendor_xiaomi_autostart to R.string.vendor_xiaomi_autostart_hint
        VendorStep.XIAOMI_BATTERY_SAVER -> R.string.vendor_xiaomi_battery to R.string.vendor_xiaomi_battery_hint
        VendorStep.DURASPEED -> R.string.vendor_duraspeed to R.string.vendor_duraspeed_hint
    }
    SetupRow(
        icon = if (opened) R.drawable.ic_check_circle else R.drawable.ic_phone_android,
        done = opened,
        title = stringResource(title),
        required = false,
        text = stringResource(hint, stringResource(R.string.app_name)),
    ) {
        if (fellBack) {
            Text(
                stringResource(R.string.vendor_fallback),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FilledTonalButton(onClick = onOpen) {
            Text(stringResource(if (opened) R.string.open_again else R.string.open_setting))
        }
    }
}

private data class RowTexts(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val why: Int,
    @param:StringRes val button: Int,
)

/** One card of the page: an icon, a title, why it matters, and what to do below. */
@Composable
private fun SetupRow(
    @DrawableRes icon: Int,
    done: Boolean,
    title: String,
    required: Boolean,
    text: String,
    action: @Composable () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (done) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (required) RequiredTag()
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                action()
            }
        }
    }
}

@Composable
private fun RequiredTag() {
    Surface(shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(
            stringResource(R.string.required),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}
