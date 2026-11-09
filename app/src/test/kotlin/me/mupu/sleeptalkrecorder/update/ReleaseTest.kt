package me.mupu.sleeptalkrecorder.update

import me.mupu.sleeptalkrecorder.whatsnew.Version
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseTest {

    private val apk = ReleaseAsset("SleepTalkRecorder.apk", "https://example.org/a.apk", 40_000_000)
    private val checksum = ReleaseAsset("SleepTalkRecorder.apk.sha256", "https://example.org/a.apk.sha256", 88)
    private val installed = Version(0, 1, 0)

    @Test
    fun `a newer release with the APK and its checksum is an update`() {
        val release = Release("v0.2.0", listOf(checksum, ReleaseAsset("notes.txt", "https://example.org/n", 3), apk))

        assertEquals(Update(Version(0, 2, 0), apk, checksum), release.updateOver(installed))
    }

    @Test
    fun `the installed version or an older one is no update`() {
        assertNull(Release("v0.1.0", listOf(apk, checksum)).updateOver(installed))
        assertNull(Release("v0.0.9", listOf(apk, checksum)).updateOver(installed))
    }

    @Test
    fun `a release without the APK or without its checksum is no update`() {
        assertNull(Release("v0.2.0", listOf(checksum)).updateOver(installed))
        assertNull(Release("v0.2.0", listOf(apk)).updateOver(installed))
        assertNull(Release("v0.2.0", listOf(apk.copy(name = "SleepTalkRecorder-debug.apk"), checksum)).updateOver(installed))
    }

    @Test
    fun `versions in tags compare as numbers, not as text`() {
        assertEquals(Version(0, 10, 0), Release("v0.10.0", listOf(apk, checksum)).updateOver(Version(0, 9, 9))?.version)
        assertEquals(Version(1, 0, 0), Release("v1.0.0", listOf(apk, checksum)).updateOver(Version(0, 99, 99))?.version)
        assertNull(Release("v0.1.9", listOf(apk, checksum)).updateOver(Version(0, 1, 10)))
    }

    @Test
    fun `a release whose tag is no version is no update`() {
        assertNull(Release("nightly", listOf(apk, checksum)).updateOver(installed))
        assertNull(Release("v1.0.0-rc.1", listOf(apk, checksum)).updateOver(installed))
    }

    @Test
    fun `the checksum is read from a sha256sum line`() {
        val hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"

        assertEquals(hex, checksumOf("$hex  SleepTalkRecorder.apk\n"))
        assertEquals(hex, checksumOf("$hex *SleepTalkRecorder.apk"))
        assertEquals(hex, checksumOf(hex.uppercase()))
    }

    @Test
    fun `a checksum file without a SHA-256 gives none`() {
        assertNull(checksumOf("not found"))
        assertNull(checksumOf("9f86d081  SleepTalkRecorder.apk"))
        assertNull(checksumOf(""))
    }
}
