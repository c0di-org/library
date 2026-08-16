package com.garfbargle.library.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INSTALL_RESULT) return
        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME).orEmpty()

        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmation = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (confirmation != null) context.startActivity(confirmation)
            }

            PackageInstaller.STATUS_SUCCESS -> {
                InstallEvents.publish(InstallEvent.Success(packageName))
            }

            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed"
                InstallEvents.publish(InstallEvent.Failure(packageName, message))
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_RESULT = "com.garfbargle.library.INSTALL_RESULT"
        const val EXTRA_PACKAGE_NAME = "package_name"
    }
}
