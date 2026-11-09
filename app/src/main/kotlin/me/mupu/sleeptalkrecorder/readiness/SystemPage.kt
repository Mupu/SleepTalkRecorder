package me.mupu.sleeptalkrecorder.readiness

/**
 * A page of the system or of a vendor app that the setup opens, described without Android types
 * so the choice of pages can be tested on the JVM. [PhoneReadiness.open] turns it into an intent.
 *
 * Settings actions are written out as strings: they are the values of the `Settings.ACTION_*`
 * constants, and some (the per-app battery page) are not public constants at all.
 */
sealed interface SystemPage {

    /**
     * An activity of another app, named by its component. With [appExtras], the intent carries
     * this app's package name as `package_name` and its label as `package_label`, which some vendor
     * pages read to show the app's own entry.
     */
    data class Activity(
        val packageName: String,
        val className: String,
        val appExtras: Boolean = false,
    ) : SystemPage

    /** A settings page opened by its intent action, about this app as [app] says. */
    data class Action(val action: String, val app: AppReference = AppReference.NONE) : SystemPage

    /** How a settings intent names the app it is about. */
    enum class AppReference {
        /** The page is not about one app. */
        NONE,

        /** As the data `package:<package name>`. */
        PACKAGE_URI,

        /** As the extra `android.provider.extra.APP_PACKAGE`. */
        PACKAGE_EXTRA,
    }

    companion object {
        /** The app's info page in the system settings; every phone has it. */
        val APP_DETAILS = Action("android.settings.APPLICATION_DETAILS_SETTINGS", AppReference.PACKAGE_URI)

        /**
         * The app's battery page ("App battery usage"), where it can be set to Unrestricted. AOSP
         * Settings exports it under a hidden action, so a phone may lack it.
         */
        val APP_BATTERY = Action("android.settings.VIEW_ADVANCED_POWER_USAGE_DETAIL", AppReference.PACKAGE_URI)

        /** The start page of the system settings. */
        val SETTINGS = Action("android.settings.SETTINGS")

        /** The app's notification settings. */
        val APP_NOTIFICATIONS = Action("android.settings.APP_NOTIFICATION_SETTINGS", AppReference.PACKAGE_EXTRA)

        /**
         * The system dialog that exempts the app from battery optimisation in one tap. It needs the
         * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which Google Play allows only for a few
         * kinds of apps; this app is installed directly, so it uses the dialog.
         */
        val BATTERY_EXEMPTION =
            Action("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", AppReference.PACKAGE_URI)

        /** Whether this app may install apps ("Install unknown apps"), which its updates need. */
        val UNKNOWN_APP_SOURCES = Action("android.settings.MANAGE_UNKNOWN_APP_SOURCES", AppReference.PACKAGE_URI)

        /** The list of all apps and whether battery optimisation applies to them. */
        val BATTERY_EXEMPTION_LIST = Action("android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS")
    }
}
