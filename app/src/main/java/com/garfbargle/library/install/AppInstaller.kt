package com.garfbargle.library.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.garfbargle.library.auth.GitHubTokenStore
import com.garfbargle.library.data.AppEntry
import com.garfbargle.library.data.DeviceApps
import com.garfbargle.library.data.normalizeFingerprint
import com.garfbargle.library.network.GitHubHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

sealed interface InstallState {
    data object Idle : InstallState
    data class Downloading(
        val progress: Float?,
        val bytesDownloaded: Long,
        val totalBytes: Long?
    ) : InstallState
    data object Verifying : InstallState
    data object Installing : InstallState
    data object AwaitingPermission : InstallState
    data object Success : InstallState
    data class Failed(val message: String) : InstallState
}

class AppInstaller(
    private val context: Context,
    private val tokenStore: GitHubTokenStore
) {
    private val deviceApps = DeviceApps(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    fun canRequestPackageInstalls(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

    fun uninstallIntent(packageName: String): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).apply {
            putExtra(Intent.EXTRA_RETURN_RESULT, true)
        }

    suspend fun install(entry: AppEntry, onState: (InstallState) -> Unit) = withContext(Dispatchers.IO) {
        val artifact = entry.preferredArtifact() ?: run {
            emit(onState, InstallState.Failed("No compatible APK is attached to this release."))
            return@withContext
        }
        val token = tokenStore.token()
        if (artifact.authRequired && token.isNullOrBlank()) {
            emit(onState, InstallState.Failed("Connect GitHub in Settings to install private releases."))
            return@withContext
        }

        val installed = deviceApps.stateFor(entry.packageName)
        if (installed.installed && !installed.signerMatches(entry)) {
            emit(onState, InstallState.Failed("The installed copy is signed differently. Remove it before installing this build."))
            return@withContext
        }

        try {
            val apk = File(context.cacheDir, "library-${entry.packageName}-${entry.versionCode}.apk")
            download(artifact.apiUrl ?: artifact.downloadUrl ?: error("Release asset URL is missing."), artifact.authRequired, token, apk) { progress, downloaded, total ->
                emit(onState, InstallState.Downloading(progress, downloaded, total))
            }
            emit(onState, InstallState.Verifying)
            verify(entry, artifact.sha256, apk)
            emit(onState, InstallState.Installing)
            stage(entry, apk)
        } catch (t: Throwable) {
            apkCleanup(entry)
            emit(onState, InstallState.Failed(t.message ?: "Installation failed"))
        }
    }

    private fun emit(onState: (InstallState) -> Unit, state: InstallState) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            onState(state)
        } else {
            mainHandler.post { onState(state) }
        }
    }

    private fun download(
        url: String,
        authRequired: Boolean,
        token: String?,
        destination: File,
        onProgress: (Float?, Long, Long?) -> Unit
    ) {
        val response = GitHubHttp.openBinary(url, if (authRequired || url.startsWith("https://api.github.com/")) token else null)
        response.use {
            val total = it.contentLength
            onProgress(total?.let { 0f }, 0L, total)
            it.stream.use { input ->
                destination.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        output.write(buffer, 0, read)
                        done += read
                        onProgress(total?.let { size -> (done.toDouble() / size.toDouble()).toFloat().coerceIn(0f, 1f) }, done, total)
                    }
                }
            }
        }
    }

    private fun verify(entry: AppEntry, expectedSha256: String, apk: File) {
        check(apk.length() > 0L) { "Downloaded APK is empty." }
        if (expectedSha256.isNotBlank()) {
            val actual = sha256(apk)
            check(actual.equals(expectedSha256.normalizeFingerprint(), ignoreCase = true)) {
                "APK integrity check failed."
            }
        }

        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val packageInfo = if (Build.VERSION.SDK_INT >= 33) {
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(apk.absolutePath, flags)
        } ?: error("Android could not inspect this APK.")

        check(packageInfo.packageName == entry.packageName) {
            "Package mismatch: expected ${entry.packageName}, got ${packageInfo.packageName}."
        }

        val archiveVersionCode = if (Build.VERSION.SDK_INT >= 28) packageInfo.longVersionCode else {
            @Suppress("DEPRECATION") packageInfo.versionCode.toLong()
        }
        check(archiveVersionCode == entry.versionCode) {
            "Version mismatch: catalog expects ${entry.versionCode}, APK contains $archiveVersionCode."
        }

        entry.signingCertSha256?.let { expected ->
            val signer = packageInfo.signingInfo?.apkContentsSigners?.firstOrNull()
                ?: error("APK signing certificate could not be read.")
            val actual = MessageDigest.getInstance("SHA-256")
                .digest(signer.toByteArray())
                .joinToString("") { "%02x".format(it) }
            check(actual.equals(expected.normalizeFingerprint(), ignoreCase = true)) {
                "Signing certificate mismatch for ${entry.name}."
            }
        }
    }

    private fun stage(entry: AppEntry, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(entry.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            apk.inputStream().buffered().use { input ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    input.copyTo(output)
                    session.fsync(output)
                }
            }

            val callback = Intent(context, InstallResultReceiver::class.java).apply {
                action = InstallResultReceiver.ACTION_INSTALL_RESULT
                putExtra(InstallResultReceiver.EXTRA_PACKAGE_NAME, entry.packageName)
            }
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                sessionId,
                callback,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            session.commit(pendingIntent.intentSender)
        }
    }

    private fun sha256(file: File): String = file.inputStream().use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun apkCleanup(entry: AppEntry) {
        File(context.cacheDir, "library-${entry.packageName}-${entry.versionCode}.apk").delete()
    }
}
