package com.mooncity.gpsmock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Restores the previous session after a reboot or an app update.
 *
 * Android 14+ often refuses to let a location-type foreground service start from the
 * background, so a failure here is expected rather than exceptional: we fall back to a
 * tappable notification instead of leaving the user with a silently dead mock.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val CHANNEL_ID = "resume"
        private const val NOTIF_ID = 4712
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (!Prefs.isActive(context)) return

        val lat = Prefs.lat(context)
        val lon = Prefs.lon(context)

        try {
            MockLocationService.start(context, lat, lon)
        } catch (e: Exception) {
            postResumeNotification(context)
        }
    }

    private fun postResumeNotification(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.channel_resume_name),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )

        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                putExtra(MainActivity.EXTRA_AUTOSTART, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val n = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setContentTitle(ctx.getString(R.string.resume_title))
            .setContentText(ctx.getString(R.string.resume_text))
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()

        nm.notify(NOTIF_ID, n)
    }
}
