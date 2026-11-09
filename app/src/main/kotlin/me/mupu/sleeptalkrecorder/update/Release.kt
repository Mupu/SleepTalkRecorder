package me.mupu.sleeptalkrecorder.update

import me.mupu.sleeptalkrecorder.whatsnew.Version
/** A published release as the release source lists it: its tag and the files attached to it. */
data class Release(val tag: String, val assets: List<ReleaseAsset>)

/** A file attached to a [Release]: its name, where to download it and its size in bytes. */
data class ReleaseAsset(val name: String, val url: String, val bytes: Long)

/** A release newer than the installed app, with the APK to install and its checksum file. */
data class Update(val version: Version, val apk: ReleaseAsset, val checksum: ReleaseAsset)

/** The name CI gives the signed release APK on every release. */
const val APK_ASSET = "SleepTalkRecorder.apk"

/** The `sha256sum` output for [APK_ASSET] that CI attaches beside it. */
const val CHECKSUM_ASSET = "$APK_ASSET.sha256"

/**
 * This release as an update over the [installed] version, or null when it is none: its tag
 * (`v0.2.0`) is no newer version, or it lacks the APK or the checksum (a release built without the
 * signing key carries neither). Releases are never pre-releases, so a tag with a pre-release part
 * is no update either.
 */
fun Release.updateOver(installed: Version): Update? {
    val version = Version.parse(tag.removePrefix("v"))?.takeIf { it > installed } ?: return null
    val apk = assets.find { it.name == APK_ASSET } ?: return null
    val checksum = assets.find { it.name == CHECKSUM_ASSET } ?: return null
    return Update(version, apk, checksum)
}

/** The SHA-256 in lower-case hex that a `sha256sum` line (`<hex>  <name>`) states, or null. */
fun checksumOf(text: String): String? =
    text.trim().substringBefore(' ').lowercase().takeIf { sha256Pattern.matches(it) }

private val sha256Pattern = Regex("[0-9a-f]{64}")
