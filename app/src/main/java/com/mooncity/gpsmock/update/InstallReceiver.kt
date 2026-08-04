package com.mooncity.gpsmock.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast
import androidx.core.content.IntentCompat
import com.mooncity.gpsmock.R

/** Receives PackageInstaller session status and surfaces the system's confirm dialog. */
class InstallReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.mooncity.gpsmock.action.INSTALL_STATUS"
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(
                    intent, Intent.EXTRA_INTENT, Intent::class.java
                )
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirm?.let { runCatching { ctx.startActivity(it) } }
            }

            PackageInstaller.STATUS_SUCCESS -> Unit // The app restarts itself.

            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit // User declined.

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.update_failed, msg ?: "?"),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
