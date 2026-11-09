package me.mupu.sleeptalkrecorder.data

/** A colour role of the app's theme that an event kind can be drawn in. */
enum class ThemeRole {
    PRIMARY,
    SECONDARY,
    TERTIARY,
}

/**
 * The colour events of a kind are drawn in: a role of the app's theme, so it follows light and
 * dark mode, or a fixed colour. It is stored as its [spec].
 */
sealed interface KindColor {

    /** How the colour is stored: the theme role in lower case, such as `tertiary`, or `#RRGGBB`. */
    val spec: String

    /** The colour of [role] in the app's current theme. */
    data class Theme(val role: ThemeRole) : KindColor {
        override val spec: String get() = role.name.lowercase()
    }

    /** The same colour in light and dark mode; [rgb] is `0xRRGGBB`. */
    data class Fixed(val rgb: Int) : KindColor {
        override val spec: String get() = "#%06X".format(rgb and 0xFFFFFF)
    }

    companion object {
        /** The colour stored as [spec]; one the app cannot read is the primary colour. */
        fun parse(spec: String): KindColor {
            ThemeRole.entries.find { it.name.equals(spec, ignoreCase = true) }?.let { return Theme(it) }
            val rgb = spec.takeIf { it.length == 7 && it.startsWith('#') }?.drop(1)?.toIntOrNull(16)
            return if (rgb != null) Fixed(rgb) else Theme(ThemeRole.PRIMARY)
        }
    }
}
