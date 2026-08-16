package com.garfbargle.library.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

class DeviceApps(private val context: Context) {
    fun stateFor(packageName: String): InstalledState {
        return try {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val info = if (Build.VERSION.SDK_INT >= 33) {
                context.packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, flags)
            }
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            val signer = info.signingInfo?.apkContentsSigners?.firstOrNull()
            InstalledState(
                installed = true,
                versionCode = code,
                versionName = info.versionName,
                signingCertSha256 = signer?.let {
                    MessageDigest.getInstance("SHA-256")
                        .digest(it.toByteArray())
                        .joinToString("") { byte -> "%02x".format(byte) }
                }
            )
        } catch (_: PackageManager.NameNotFoundException) {
            InstalledState(installed = false)
        }
    }
}
