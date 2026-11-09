package me.mupu.sleeptalkrecorder.update

import me.mupu.sleeptalkrecorder.whatsnew.Version
import me.mupu.sleeptalkrecorder.models.ByteSource
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where the app's releases are listed. */
fun interface ReleaseSource {

    /** The newest release, or null when none is published. */
    @Throws(IOException::class)
    fun latest(): Release?
}

/** An app as the package manager knows it: its package, version code and signing certificates. */
data class ApkIdentity(
    val packageName: String,
    val versionCode: Long,
    /** The SHA-256 of each signing certificate. */
    val signers: Set<String>,
)

/** Reads who an APK file is and who the installed app is. */
interface ApkInspector {

    /** The running app. */
    fun installed(): ApkIdentity

    /** The app in the APK [file], or null when it is no readable APK. */
    fun identity(file: File): ApkIdentity?
}

/** Hands a verified APK to the system's package installer; the outcome arrives through [Updater.installFinished]. */
fun interface PackageInstall {
    fun install(apk: File, trigger: Trigger)
}

/** Where the update stands, as home and the settings show it. */
sealed interface UpdateState {

    /** This build does not update itself (debug builds without a test source). */
    data object Off : UpdateState

    /** Not checked since the app started. */
    data object Unchecked : UpdateState

    data object Checking : UpdateState

    /** The installed version is the newest release. */
    data object UpToDate : UpdateState

    /** A newer version waits for a tap to be downloaded, because the network is metered. */
    data class Available(val version: Version, val bytes: Long) : UpdateState

    data class Downloading(val version: Version, val done: Long, val total: Long) : UpdateState

    /**
     * A newer version is downloaded and verified and installs on a tap. With [waitsForTap] the
     * system wanted a confirmation the app could not show from the background, so it no longer
     * installs it on its own.
     */
    data class Ready(val version: Version, val waitsForTap: Boolean = false) : UpdateState

    /** The system installs it; the app is closed when it succeeds. */
    data class Installing(val version: Version) : UpdateState

    /** The last step failed; [version] is the update it was about, if one was found. */
    data class Failed(val problem: UpdateProblem, val version: Version?) : UpdateState
}

/** Why an update step failed. */
enum class UpdateProblem {
    /** The release source could not be reached. */
    CHECK_FAILED,

    /** The connection or the storage failed during the download; it continues on the next try. */
    DOWNLOAD_FAILED,

    /** The APK did not have the checksum published beside it. */
    DAMAGED,

    /** The APK is not signed with the installed app's certificate, so Android would refuse it. */
    OTHER_SIGNER,

    /** The APK is another app, no newer version or no APK at all. */
    NOT_AN_UPDATE,

    /** The system's installer reported a failure. */
    INSTALL_FAILED,
}

/** How the system's installer answered an install. */
enum class InstallOutcome {
    /** The system shows its confirmation dialog. */
    CONFIRMING,

    /** The system wants a confirmation that cannot be shown while the app is in the background. */
    NEEDS_TAP,

    /** The user cancelled the confirmation. */
    CANCELLED,

    FAILED,
}

/**
 * Keeps the app up to date from its releases: checks the [source], downloads the APK of a newer
 * version into [directory], verifies it and installs it.
 *
 * A download is kept as `<version>.apk.part` and continued where it stopped; it becomes
 * `<version>.apk` only once its SHA-256 matched the published checksum and it is this app, newer,
 * and signed with the installed app's certificates. So a file under its final name is always
 * verified: a later start finds it ready at once, also offline or without a check, and installs it
 * without downloading again.
 *
 * When downloads and installs happen is [UpdatePolicy]'s decision; [activity] tells what the app
 * is doing. One step runs at a time.
 */
class Updater(
    private val installedVersion: Version,
    /** Null when this build does not update itself. */
    private val source: ReleaseSource?,
    private val files: ByteSource,
    private val directory: File,
    private val apks: ApkInspector,
    private val installer: PackageInstall,
    /** Whether the current network is metered (mobile data). */
    private val metered: () -> Boolean,
    private val activity: StateFlow<AppActivity>,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    private val mutableState = MutableStateFlow(if (source == null) UpdateState.Off else UpdateState.Unchecked)

    /** Where the update stands. */
    val state: StateFlow<UpdateState> = mutableState.asStateFlow()

    /** The update found by the last check. */
    private var update: Update? = null
    private var work: Job? = null

    /** Looks for a verified download that an earlier start left; every check waits for it. */
    private val restored: Job? = source?.let { scope.launch { restoreDownload() } }

    init {
        if (source != null) scope.launch { installWhenIdle() }
    }

    /**
     * Looks for a newer release and downloads it if [UpdatePolicy.mayDownload] lets the app do so
     * on its own; on a metered network the download waits for [download].
     */
    fun check() {
        val releases = source ?: return
        runStep {
            restored?.join()
            // A downloaded update stays offered while the check runs, and when it fails.
            val ready = mutableState.value as? UpdateState.Ready
            if (ready == null) mutableState.value = UpdateState.Checking
            val found = try {
                withContext(io) { releases.latest()?.updateOver(installedVersion) }
            } catch (_: IOException) {
                mutableState.value = ready ?: UpdateState.Failed(UpdateProblem.CHECK_FAILED, null)
                return@runStep
            }
            update = found
            withContext(io) { removeOtherDownloads(found?.version) }
            when {
                found == null -> mutableState.value = UpdateState.UpToDate
                withContext(io) { verified(found) } -> mutableState.value = UpdateState.Ready(found.version)
                UpdatePolicy.mayDownload(Trigger.APP, metered()) -> fetch(found)
                else -> mutableState.value = UpdateState.Available(found.version, found.apk.bytes)
            }
        }
    }

    /** Downloads the update found, on a tap; also after a failed download. */
    fun download() {
        val found = update ?: return
        runStep { fetch(found) }
    }

    /** Installs the verified update on a tap, unless [UpdatePolicy.mayInstall] forbids it. */
    fun install() {
        install(Trigger.USER)
    }

    /** Tells how the system's installer answered the last install. */
    fun installFinished(outcome: InstallOutcome) {
        val installing = mutableState.value as? UpdateState.Installing ?: return
        mutableState.value = when (outcome) {
            InstallOutcome.CONFIRMING -> installing
            InstallOutcome.NEEDS_TAP -> UpdateState.Ready(installing.version, waitsForTap = true)
            InstallOutcome.CANCELLED -> UpdateState.Ready(installing.version, waitsForTap = true)
            InstallOutcome.FAILED -> UpdateState.Failed(UpdateProblem.INSTALL_FAILED, installing.version)
        }
    }

    private fun install(trigger: Trigger) {
        val version = when (val current = mutableState.value) {
            is UpdateState.Ready -> current.version
            is UpdateState.Failed -> current.version.takeIf { current.problem == UpdateProblem.INSTALL_FAILED }
            else -> null
        } ?: return
        if (work?.isActive == true || !UpdatePolicy.mayInstall(trigger, activity.value)) return

        mutableState.value = UpdateState.Installing(version)
        // The installer copies the whole APK into its session, so it runs off the main thread.
        runStep { withContext(io) { installer.install(apkFile(version), trigger) } }
    }

    /** Offers the newest verified download of a version above the installed one, if there is one. */
    private suspend fun restoreDownload() {
        val version = withContext(io) {
            val newest = directory.listFiles().orEmpty()
                .filter { it.name.endsWith(".apk") }
                .mapNotNull { Version.parse(it.name.removeSuffix(".apk")) }
                .filter { it > installedVersion }
                .maxOrNull() ?: return@withContext null
            newest.takeIf { problemWith(apkFile(it)) == null }
        } ?: return
        if (mutableState.value == UpdateState.Unchecked) mutableState.value = UpdateState.Ready(version)
    }

    /** Installs a ready update on its own once the app has been in the background for a while with nothing running. */
    private suspend fun installWhenIdle() {
        combine(mutableState, activity, ::Pair).collectLatest { (state, current) ->
            if (state !is UpdateState.Ready || state.waitsForTap) return@collectLatest
            if (!UpdatePolicy.mayInstall(Trigger.APP, current)) return@collectLatest
            // A screen rotation or a quick look at another app leaves the app only for a moment.
            delay(BACKGROUND_DELAY_MS)
            install(Trigger.APP)
        }
    }

    /** Runs [step] unless another step is running. */
    private fun runStep(step: suspend () -> Unit) {
        if (work?.isActive == true) return
        work = scope.launch { step() }
    }

    private suspend fun fetch(found: Update) {
        val version = found.version
        mutableState.value = UpdateState.Downloading(version, 0, found.apk.bytes)
        val problem = try {
            withContext(io) {
                val checksum = files.open(found.checksum.url, 0).use { checksumOf(it.stream.reader().readText()) }
                    ?: return@withContext UpdateProblem.DAMAGED
                val partial = File(directory, "$version.apk.part")
                downloadInto(partial, found) { done -> mutableState.value = UpdateState.Downloading(version, done, found.apk.bytes) }
                acceptOrDelete(partial, checksum, version)
            }
        } catch (_: IOException) {
            UpdateProblem.DOWNLOAD_FAILED
        }
        mutableState.value = if (problem == null) UpdateState.Ready(version) else UpdateState.Failed(problem, version)
    }

    /** Appends the rest of the APK to [partial], resuming where an earlier download stopped. */
    private suspend fun downloadInto(partial: File, found: Update, onBytes: (Long) -> Unit) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        if (partial.length() >= found.apk.bytes) partial.delete()

        files.open(found.apk.url, partial.length()).use { body ->
            // A body from offset 0 is the whole file again, so it replaces what is there.
            FileOutputStream(partial, body.offset > 0).use { output ->
                val buffer = ByteArray(BUFFER_BYTES)
                var written = body.offset
                while (true) {
                    coroutineContext.ensureActive()
                    val read = body.stream.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    written += read
                    onBytes(written)
                }
            }
        }
        if (partial.length() < found.apk.bytes) throw IOException("Connection ended early")
    }

    /** Renames [partial] to the verified APK of [version], or deletes it and says why it was refused. */
    private fun acceptOrDelete(partial: File, checksum: String, version: Version): UpdateProblem? {
        val problem = if (sha256(partial) != checksum) UpdateProblem.DAMAGED else problemWith(partial)
        if (problem != null) {
            partial.delete()
            return problem
        }
        if (!partial.renameTo(apkFile(version))) throw IOException("Cannot rename $partial")
        return null
    }

    /** Whether the verified APK of [found] is already here; a file that no longer passes is deleted. */
    private fun verified(found: Update): Boolean {
        val file = apkFile(found.version)
        if (!file.isFile) return false
        if (problemWith(file) == null) return true
        file.delete()
        return false
    }

    private fun problemWith(file: File): UpdateProblem? {
        val app = apks.installed()
        val apk = apks.identity(file) ?: return UpdateProblem.NOT_AN_UPDATE
        return when {
            apk.packageName != app.packageName || apk.versionCode <= app.versionCode -> UpdateProblem.NOT_AN_UPDATE
            apk.signers != app.signers -> UpdateProblem.OTHER_SIGNER
            else -> null
        }
    }

    /** Deletes downloads of versions other than [keep], such as the APK of the update just installed. */
    private fun removeOtherDownloads(keep: Version?) {
        directory.listFiles().orEmpty()
            .filter { keep == null || !it.name.startsWith("$keep.apk") }
            .forEach { it.delete() }
    }

    private fun apkFile(version: Version) = File(directory, "$version.apk")

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_BYTES)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** How long the app waits in the background before it installs a ready update on its own. */
        const val BACKGROUND_DELAY_MS = 10_000L

        private const val BUFFER_BYTES = 64 * 1024
    }
}
