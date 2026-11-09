package me.mupu.sleeptalkrecorder.update

import me.mupu.sleeptalkrecorder.whatsnew.Version
import me.mupu.sleeptalkrecorder.models.ByteSource
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {

    private val directory: File = Files.createTempDirectory("updates").toFile()
    private val app = ApkIdentity(PACKAGE, versionCode = 1_000, signers = setOf("our-key"))
    private val newApk = "new app".toByteArray()

    /** What the fake APK files are, by their content. */
    private val apks = mutableMapOf("new app" to ApkIdentity(PACKAGE, 2_000, setOf("our-key")))
    private val files = mutableMapOf("https://e.org/apk" to newApk, "https://e.org/sha" to "${sha256(newApk)}  $APK_ASSET".toByteArray())
    private var metered = false
    private val installed = mutableListOf<File>()
    private val activity = MutableStateFlow(AppActivity(recording = false, playing = false, transcribing = false, visible = true))

    private val release = Release(
        "v0.2.0",
        listOf(ReleaseAsset(APK_ASSET, "https://e.org/apk", newApk.size.toLong()), ReleaseAsset(CHECKSUM_ASSET, "https://e.org/sha", 80)),
    )

    private var offline = false

    private fun TestScope.updater() = Updater(
        installedVersion = Version(0, 1, 0),
        source = { if (offline) throw IOException("offline") else release },
        files = { url, _ -> ByteSource.Body(0, (files[url] ?: throw IOException("404 $url")).inputStream()) },
        directory = directory,
        apks = object : ApkInspector {
            override fun installed() = app
            override fun identity(file: File) = apks[file.readText()]
        },
        installer = { file, _ -> installed += file },
        metered = { metered },
        activity = activity,
        scope = backgroundScope,
        io = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun `a newer release downloads on Wi-Fi, is verified and installs on a tap`() = runTest {
        val updater = updater()

        updater.check()
        runCurrent()
        assertEquals(UpdateState.Ready(Version(0, 2, 0)), updater.state.value)

        updater.install()
        runCurrent()
        assertEquals(listOf("new app"), installed.map { it.readText() })
    }

    @Test
    fun `on mobile data the download waits for a tap`() = runTest {
        metered = true
        val updater = updater()

        updater.check()
        runCurrent()
        assertEquals(UpdateState.Available(Version(0, 2, 0), newApk.size.toLong()), updater.state.value)

        updater.download()
        runCurrent()
        assertEquals(UpdateState.Ready(Version(0, 2, 0)), updater.state.value)
    }

    @Test
    fun `a download with the wrong checksum is refused and deleted`() = runTest {
        files["https://e.org/sha"] = "${sha256("other".toByteArray())}  $APK_ASSET".toByteArray()
        val updater = updater()

        updater.check()
        runCurrent()

        assertEquals(UpdateState.Failed(UpdateProblem.DAMAGED, Version(0, 2, 0)), updater.state.value)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        updater.install()
        runCurrent()
        assertTrue(installed.isEmpty())
    }

    @Test
    fun `an APK signed with another certificate is refused`() = runTest {
        apks["new app"] = ApkIdentity(PACKAGE, 2_000, setOf("someone-else"))
        val updater = updater()

        updater.check()
        runCurrent()

        assertEquals(UpdateState.Failed(UpdateProblem.OTHER_SIGNER, Version(0, 2, 0)), updater.state.value)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun `a ready update installs on its own a while after the app went to the background`() = runTest {
        val updater = updater()
        updater.check()
        runCurrent()

        activity.value = activity.value.copy(visible = false)
        advanceTimeBy(Updater.BACKGROUND_DELAY_MS - 1)
        assertTrue(installed.isEmpty())

        advanceTimeBy(2)
        runCurrent()
        assertEquals(1, installed.size)
    }

    @Test
    fun `a downloaded update is ready on the next start, also when the check fails`() = runTest {
        updater().check()
        runCurrent()

        offline = true
        val next = updater()
        runCurrent()
        assertEquals(UpdateState.Ready(Version(0, 2, 0)), next.state.value)

        next.check()
        runCurrent()
        assertEquals(UpdateState.Ready(Version(0, 2, 0)), next.state.value)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val PACKAGE = "me.mupu.sleeptalkrecorder"
    }
}
