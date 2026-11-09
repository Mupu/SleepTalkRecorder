package me.mupu.sleeptalkrecorder.readiness

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import androidx.core.net.toUri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone lets the app record a whole night: which [Requirement]s are missing and which
 * [VendorStep]s the phone needs.
 *
 * The user can change permissions and battery settings at any time outside the app, so
 * `MainActivity` calls [refresh] whenever it comes to the front, also on return from a system
 * dialog or settings page.
 */
class PhoneReadiness(private val context: Context) {

    private val missingRequirements = MutableStateFlow(check())

    /** The requirements the phone does not meet at the last [refresh]. */
    val missing: StateFlow<Set<Requirement>> = missingRequirements.asStateFlow()

    /** The vendor steps this phone needs; its maker and firmware do not change while the app runs. */
    val vendorSteps: List<VendorStep> = VendorStep.forPhone(Build.MANUFACTURER, ::isInstalled)

    /** Checks the requirements again. */
    fun refresh() {
        missingRequirements.value = check()
    }

    private fun check(): Set<Requirement> = buildSet {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            add(Requirement.MICROPHONE)
        }
        // False both without the permission (Android 13 and later) and when the user turned the
        // app's notifications off in the settings.
        if (!context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            add(Requirement.NOTIFICATIONS)
        }
        if (!context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)) {
            add(Requirement.BATTERY_OPTIMISATION)
        }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

/**
 * Opens the first of [pages] that this context can open and returns it, or null when none opened.
 * A page that is missing, or that its app does not let other apps open, is skipped.
 */
fun Context.openFirst(pages: List<SystemPage>): SystemPage? = pages.firstOrNull { page ->
    try {
        startActivity(intentFor(page))
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        // A vendor activity that is not exported, or that needs a system permission.
        false
    }
}

private fun Context.intentFor(page: SystemPage): Intent = when (page) {
    is SystemPage.Activity -> Intent().apply {
        component = ComponentName(page.packageName, page.className)
        if (page.appExtras) {
            putExtra("package_name", packageName)
            putExtra("package_label", applicationInfo.loadLabel(packageManager))
        }
    }
    is SystemPage.Action -> Intent(page.action).apply {
        when (page.app) {
            SystemPage.AppReference.NONE -> Unit
            SystemPage.AppReference.PACKAGE_URI -> data = "package:$packageName".toUri()
            SystemPage.AppReference.PACKAGE_EXTRA -> putExtra("android.provider.extra.APP_PACKAGE", packageName)
        }
    }
}
