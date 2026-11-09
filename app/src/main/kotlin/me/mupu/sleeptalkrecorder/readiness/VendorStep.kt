package me.mupu.sleeptalkrecorder.readiness

/**
 * A setting outside Android's own battery optimisation with which a vendor's system ends apps in
 * the background, and the pages that lead to it.
 *
 * [pages] are tried in order until one opens: first the vendor's own page, last a page every phone
 * of that vendor has, so a renamed or missing vendor page still leaves the user one step from the
 * setting. The vendor components come from vendor firmware, not from any documentation, and are
 * not checked against real phones; whether the setting worked cannot be read back either, so a
 * vendor step never counts as missing. The manifest lists the vendor packages under `<queries>`,
 * because Android hides other apps from this one otherwise.
 */
enum class VendorStep(val pages: List<SystemPage>) {

    /** Samsung One UI: Battery, Background usage limits, Never sleeping apps. */
    SAMSUNG_NEVER_SLEEPING(
        listOf(
            // One UI 3 and later, then One UI 1 and 2 (Device care, Battery).
            SystemPage.Activity(SAMSUNG_DEVICE_CARE, "com.samsung.android.sm.battery.ui.BatteryActivity"),
            SystemPage.Activity(SAMSUNG_DEVICE_CARE, "com.samsung.android.sm.ui.battery.BatteryActivity"),
            SystemPage.APP_BATTERY,
            SystemPage.APP_DETAILS,
        ),
    ),

    /** Xiaomi MIUI and HyperOS: Autostart, without which the system does not restart the app. */
    XIAOMI_AUTOSTART(
        listOf(
            SystemPage.Activity("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            SystemPage.APP_DETAILS,
        ),
    ),

    /** Xiaomi MIUI and HyperOS: the app's battery saver set to "No restrictions". */
    XIAOMI_BATTERY_SAVER(
        listOf(
            SystemPage.Activity("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity", appExtras = true),
            SystemPage.APP_BATTERY,
            SystemPage.APP_DETAILS,
        ),
    ),

    /**
     * MediaTek DuraSpeed, which stops background apps to speed up the one in front; it is an entry
     * of its own in the system settings. In MediaTek's reference firmware its page is guarded by a
     * system-only permission, so the start page of the settings is the usual way there.
     */
    DURASPEED(
        listOf(
            SystemPage.Activity(MEDIATEK_DURASPEED, "com.mediatek.duraspeed.DuraSpeedMainActivity"),
            SystemPage.SETTINGS,
            SystemPage.APP_DETAILS,
        ),
    ),
    ;

    companion object {
        /** The package of MediaTek's DuraSpeed; a phone that has it installed gets [DURASPEED]. */
        const val DURASPEED_PACKAGE = MEDIATEK_DURASPEED

        /**
         * The steps a phone made by [manufacturer] (`Build.MANUFACTURER`) needs, in the order the
         * setup shows them; [isInstalled] tells whether a package is on the phone.
         */
        fun forPhone(manufacturer: String, isInstalled: (String) -> Boolean): List<VendorStep> = buildList {
            when (manufacturer.lowercase()) {
                "samsung" -> add(SAMSUNG_NEVER_SLEEPING)
                // Redmi and POCO are Xiaomi brands with the same system.
                "xiaomi", "redmi", "poco" -> addAll(listOf(XIAOMI_AUTOSTART, XIAOMI_BATTERY_SAVER))
            }
            // DuraSpeed is part of MediaTek's firmware, which many makers use, so it is found by
            // its package rather than by maker.
            if (isInstalled(DURASPEED_PACKAGE)) add(DURASPEED)
        }
    }
}

/** Samsung's Device care app, which holds the battery settings. */
private const val SAMSUNG_DEVICE_CARE = "com.samsung.android.lool"

/** MediaTek's DuraSpeed app. */
private const val MEDIATEK_DURASPEED = "com.mediatek.duraspeed"
