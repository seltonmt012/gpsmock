package com.mooncity.gpsmock.trip

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.mooncity.gpsmock.MockLocationService
import com.mooncity.gpsmock.Notifier
import com.mooncity.gpsmock.Prefs
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Arms an alarm shortly before each scheduled departure.
 *
 * Android 14+ frequently refuses to let a location foreground service start from the
 * background, so auto-start is attempted and, when it is blocked, downgraded to a
 * notification rather than failing silently.
 */
object TripAlarms {

    private const val REQUEST_CODE = 8100

    /** Fire slightly early so the service is already pushing fixes at departure time. */
    private const val LEAD_MILLIS = 60_000L

    fun reschedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(ctx)

        val trip = Trip.load(ctx)
        if (trip == null || trip.instantStartMillis != null) {
            am.cancel(pending)
            return
        }

        val next = Schedule.nextDeparture(trip, ZonedDateTime.now(Schedule.zone()))
        if (next == null) {
            am.cancel(pending)
            return
        }

        val at = next.toInstant().toEpochMilli() - LEAD_MILLIS
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                // Inexact is still good enough to nudge the user a few minutes out.
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    fun cancel(ctx: Context) {
        ctx.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(ctx))
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx,
        REQUEST_CODE,
        Intent(ctx, TripAlarmReceiver::class.java).setAction(TripAlarmReceiver.ACTION),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
}

class TripAlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION = "com.mooncity.gpsmock.action.TRIP_DUE"
        private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }

    override fun onReceive(ctx: Context, intent: Intent?) {
        val trip = Trip.load(ctx)
        if (trip == null) {
            TripAlarms.cancel(ctx)
            return
        }

        val now = ZonedDateTime.now(Schedule.zone())
        val label = Schedule.currentOccurrence(trip, now)?.outStart?.format(HHMM)
            ?: Schedule.nextDeparture(trip, now)?.format(HHMM)
            ?: "?"

        when {
            MockLocationService.isRunning -> Unit // Already covering the departure.

            Prefs.autoStart(ctx) -> {
                try {
                    Prefs.setMode(ctx, Prefs.MODE_TRIP)
                    MockLocationService.startTrip(ctx)
                } catch (e: Exception) {
                    // Android 14+ background start restriction, most likely.
                    Notifier.tripAutoStartBlocked(ctx)
                }
            }

            else -> Notifier.tripDue(ctx, label)
        }

        // Chain the next departure; alarms are one-shot.
        TripAlarms.reschedule(ctx)
    }
}

/** Convenience for callers that only have epoch millis. */
fun millisToZoned(millis: Long): ZonedDateTime =
    Instant.ofEpochMilli(millis).atZone(Schedule.zone())
