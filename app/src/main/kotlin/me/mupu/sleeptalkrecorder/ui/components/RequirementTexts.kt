package me.mupu.sleeptalkrecorder.ui.components

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import me.mupu.sleeptalkrecorder.R
import me.mupu.sleeptalkrecorder.readiness.Requirement

/** How the app shows a [Requirement]: its icon, its name, why it is needed and the button that grants it. */
internal data class RequirementTexts(
    @param:DrawableRes val icon: Int,
    @param:StringRes val title: Int,
    @param:StringRes val why: Int,
    @param:StringRes val button: Int,
)

/** The icon and texts of this requirement, the same on every screen. */
internal val Requirement.texts: RequirementTexts
    get() = when (this) {
        Requirement.MICROPHONE ->
            RequirementTexts(R.drawable.ic_mic, R.string.requirement_microphone, R.string.requirement_microphone_why, R.string.allow)
        Requirement.NOTIFICATIONS ->
            RequirementTexts(R.drawable.ic_notifications, R.string.requirement_notifications, R.string.requirement_notifications_why, R.string.allow)
        Requirement.BATTERY_OPTIMISATION ->
            RequirementTexts(R.drawable.ic_battery, R.string.requirement_battery, R.string.requirement_battery_why, R.string.allow_exemption)
    }
