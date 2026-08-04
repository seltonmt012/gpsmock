package com.mooncity.gpsmock

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Alerts for things the user would otherwise only find out about by opening the app:
 * the mock dying, the permission being revoked, or a trip departing while nothing runs.
 */
object Notifier {

    private const val CHANNEL_EVENTS = "events"

    const val ID_STOPPED = 5001
    const val ID_ERROR = 5002
    const val ID_TRIP_DUE = 5003
    const val ID_TRIP_DONE = 5004

    private fun manager(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_EVENTS,
                ctx.getString(R.string.channel_events_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = ctx.getString(R.string.channel_events_desc) }
        )
        return nm
    }

    private fun openApp(ctx: Context, requestCode: Int, autostart: Boolean = false): PendingIntent =
        PendingIntent.getActivity(
            ctx, requestCode,
            Intent(ctx, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                if (autostart) putExtra(MainActivity.EXTRA_AUTOSTART, true)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun post(
        ctx: Context,
        id: Int,
        title: String,
        text: String,
        intent: PendingIntent
    ) {
        val n = NotificationCompat.Builder(ctx, CHANNEL_EVENTS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        runCatching { manager(ctx).notify(id, n) }
    }

    /** The simulation ended without the user asking for it. */
    fun mockStopped(ctx: Context, reason: String) = post(
        ctx,
        ID_STOPPED,
        ctx.getString(R.string.notify_stopped_title),
        reason,
        openApp(ctx, 10)
    )

    /** Android revoked the mock permission, so nothing can be simulated any more. */
    fun permissionLost(ctx: Context) = post(
        ctx,
        ID_ERROR,
        ctx.getString(R.string.notify_permission_title),
        ctx.getString(R.string.notify_permission_text),
        openApp(ctx, 11)
    )

    /** A scheduled departure is due but the service is not running. */
    fun tripDue(ctx: Context, timeLabel: String) = post(
        ctx,
        ID_TRIP_DUE,
        ctx.getString(R.string.notify_trip_due_title),
        ctx.getString(R.string.notify_trip_due_text, timeLabel),
        openApp(ctx, 12, autostart = true)
    )

    /** Auto-start was allowed but Android refused the background start. */
    fun tripAutoStartBlocked(ctx: Context) = post(
        ctx,
        ID_TRIP_DUE,
        ctx.getString(R.string.notify_autostart_blocked_title),
        ctx.getString(R.string.notify_autostart_blocked_text),
        openApp(ctx, 13, autostart = true)
    )

    fun tripFinished(ctx: Context) = post(
        ctx,
        ID_TRIP_DONE,
        ctx.getString(R.string.notify_trip_done_title),
        ctx.getString(R.string.notify_trip_done_text),
        openApp(ctx, 14)
    )

    fun clear(ctx: Context, id: Int) = runCatching {
        ctx.getSystemService(NotificationManager::class.java).cancel(id)
    }
}
