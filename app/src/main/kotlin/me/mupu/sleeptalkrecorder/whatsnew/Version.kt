package me.mupu.sleeptalkrecorder.whatsnew

/** A Semantic Versioning release number of the app, as `CHANGELOG.md` names it: "0.1.0". */
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {

    override fun compareTo(other: Version): Int = compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val PATTERN = Regex("""(\d+)\.(\d+)\.(\d+)""")

        /** The version written as "major.minor.patch", or null for anything else. */
        fun parse(text: String): Version? {
            val (major, minor, patch) = PATTERN.matchEntire(text.trim())?.destructured ?: return null
            return Version(major.toIntOrNull() ?: return null, minor.toIntOrNull() ?: return null, patch.toIntOrNull() ?: return null)
        }
    }
}
