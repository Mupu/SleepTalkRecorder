package me.mupu.sleeptalkrecorder.readiness

/**
 * How a runtime permission the app lacks is asked for.
 *
 * After the user refused a permission twice (or chose "Don't ask again"), Android no longer shows
 * its dialog and refuses at once; only the app's settings page can grant the permission then.
 * Android tells the two cases apart only by `shouldShowRequestPermissionRationale`, which is
 * false both before the first request and after the final refusal, so the app remembers whether
 * it asked before.
 */
enum class PermissionPrompt {
    /** The system's permission dialog. */
    DIALOG,

    /** The app's page in the system settings. */
    SETTINGS,
    ;

    companion object {
        /**
         * The prompt for a permission the app [askedBefore] or not, where [systemWouldExplain] is
         * what `shouldShowRequestPermissionRationale` returns: true after a single refusal.
         */
        fun of(askedBefore: Boolean, systemWouldExplain: Boolean): PermissionPrompt =
            if (askedBefore && !systemWouldExplain) SETTINGS else DIALOG
    }
}
