package me.mupu.sleeptalkrecorder.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.util.Log
import me.mupu.sleeptalkrecorder.SleepTalkApplication
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import org.json.JSONException
import org.json.JSONObject

/**
 * The newest release from a GitHub-style "latest release" endpoint at [url]
 * (`GET /repos/{owner}/{repo}/releases/latest`): GitHub lists there the newest release that is
 * neither a draft nor a pre-release. Anonymous calls are limited to 60 an hour per address, far
 * more than a check per app start needs.
 */
class GitHubReleases(private val url: String, private val userAgent: String) : ReleaseSource {

    override fun latest(): Release? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        // GitHub rejects API calls without a User-Agent.
        connection.setRequestProperty("User-Agent", userAgent)
        try {
            return when (val status = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> parse(connection.inputStream.bufferedReader().use { it.readText() })
                // No release is published yet.
                HttpURLConnection.HTTP_NOT_FOUND -> null
                else -> throw IOException("HTTP $status for $url")
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: String): Release? = try {
        val release = JSONObject(json)
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) {
            null
        } else {
            val assets = release.optJSONArray("assets")
            Release(
                tag = release.getString("tag_name"),
                assets = List(assets?.length() ?: 0) { index ->
                    val asset = assets!!.getJSONObject(index)
                    ReleaseAsset(asset.getString("name"), asset.getString("browser_download_url"), asset.getLong("size"))
                },
            )
        }
    } catch (failure: JSONException) {
        throw IOException("Unreadable release from $url", failure)
    }

    private companion object {
        const val TIMEOUT_MS = 30_000
    }
}

/** Reads package names, version codes and signing certificates from the package manager. */
class PackageApks(private val context: Context) : ApkInspector {

    override fun installed(): ApkIdentity {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(SIGNATURES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageInfo(context.packageName, SIGNATURES)
        }
        return checkNotNull(identityOf(info)) { "The installed app has no signing information" }
    }

    override fun identity(file: File): ApkIdentity? {
        val manager = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getPackageArchiveInfo(file.path, PackageManager.PackageInfoFlags.of(SIGNATURES.toLong()))
        } else {
            @Suppress("DEPRECATION")
            manager.getPackageArchiveInfo(file.path, SIGNATURES)
        }
        return info?.let(::identityOf)
    }

    private fun identityOf(info: PackageInfo): ApkIdentity? {
        val signers = info.signingInfo?.apkContentsSigners ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
        return ApkIdentity(
            packageName = info.packageName,
            versionCode = info.longVersionCode,
            signers = signers.map { signer -> digest.digest(signer.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet(),
        )
    }

    private companion object {
        const val SIGNATURES = PackageManager.GET_SIGNING_CERTIFICATES
    }
}

/**
 * Installs an APK over this app through a [PackageInstaller] session. The session asks for no
 * user action: Android then installs without a dialog when the app holds "Install unknown apps",
 * declares `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, updates itself and the APK targets a recent API
 * level. Otherwise it answers with a confirmation, which [InstallStatusReceiver] handles. Every
 * install kills the app's processes, also on success, so success is never reported back.
 */
class SessionInstaller(private val context: Context, private val onOutcome: (InstallOutcome) -> Unit) : PackageInstall {

    override fun install(apk: File, trigger: Trigger) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        var sessionId: Int? = null
        try {
            val id = installer.createSession(params).also { sessionId = it }
            installer.openSession(id).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                session.commit(InstallStatusReceiver.statusSender(context, id, trigger))
            }
        } catch (failure: IOException) {
            fail(installer, sessionId, failure)
        } catch (failure: SecurityException) {
            fail(installer, sessionId, failure)
        }
    }

    private fun fail(installer: PackageInstaller, sessionId: Int?, failure: Exception) {
        Log.w(TAG, "Cannot install the update", failure)
        sessionId?.let { runCatching { installer.abandonSession(it) } }
        onOutcome(InstallOutcome.FAILED)
    }
}

/**
 * Receives the answer of the system's installer to a session of [SessionInstaller] and tells the
 * updater. A confirmation is shown only for an install the user tapped while the app is on the
 * screen; from the background Android would not let the app open it, so the session is given up
 * and the update waits for a tap.
 */
class InstallStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as SleepTalkApplication).container
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val outcome = when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = confirmationOf(intent)
                val tapped = intent.getStringExtra(KEY_TRIGGER) == Trigger.USER.name
                if (confirmation != null && tapped && container.appVisible.value) {
                    context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    InstallOutcome.CONFIRMING
                } else {
                    val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                    runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
                    InstallOutcome.NEEDS_TAP
                }
            }
            // The system kills the app before it could see this, but it is harmless.
            PackageInstaller.STATUS_SUCCESS -> return
            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallOutcome.CANCELLED
            else -> {
                Log.w(TAG, "Install failed: $status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}")
                InstallOutcome.FAILED
            }
        }
        container.updater.installFinished(outcome)
    }

    private fun confirmationOf(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
        }

    companion object {
        private const val KEY_TRIGGER = "trigger"

        /**
         * Where the system reports on session [sessionId]. The intent must be mutable, because the
         * system adds the status to it.
         */
        fun statusSender(context: Context, sessionId: Int, trigger: Trigger) = PendingIntent.getBroadcast(
            context,
            sessionId,
            Intent(context, InstallStatusReceiver::class.java).putExtra(KEY_TRIGGER, trigger.name),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        ).intentSender
    }
}

/** Whether the network the phone uses now is metered, such as mobile data; true without a network. */
fun isMetered(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered

private const val TAG = "Update"
