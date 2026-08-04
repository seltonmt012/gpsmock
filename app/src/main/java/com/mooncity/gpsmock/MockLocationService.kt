package com.mooncity.gpsmock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.mooncity.gpsmock.trip.Fix
import com.mooncity.gpsmock.trip.Phase
import com.mooncity.gpsmock.trip.Trip
import com.mooncity.gpsmock.trip.TripSource
import kotlin.random.Random

/**
 * Pushes a fixed position into Android's test location providers.
 *
 * Runs as a foreground service and holds a partial wake lock, because the usual failure
 * mode of mock location apps is the OS freezing their update loop while the screen is off:
 * consumers then fall back to the real fix. The loop also re-registers providers on the fly
 * if the system drops them, so a single hiccup does not end the session.
 */
class MockLocationService : Service() {

    companion object {
        private const val TAG = "MockLocationService"

        const val ACTION_START = "com.mooncity.gpsmock.action.START"
        const val ACTION_STOP = "com.mooncity.gpsmock.action.STOP"
        const val ACTION_UPDATE = "com.mooncity.gpsmock.action.UPDATE"

        /** Broadcast sent whenever the running state or the error state changes. */
        const val BROADCAST_STATUS = "com.mooncity.gpsmock.STATUS"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_MODE = "mode"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIF_ID = 4711

        /** How often a fresh fix is injected. Snapchat and friends discard stale fixes. */
        private const val UPDATE_INTERVAL_MS = 900L

        /** ~1.2 m of wobble, so the position is not suspiciously frozen. */
        private const val JITTER_DEG = 0.000011

        @Volatile
        var isRunning = false
            private set

        @Volatile
        var lastError: String? = null
            private set

        private val PROVIDERS: List<String> = buildList {
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            // The fused provider is what most apps actually read from on modern Android.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add("fused")
        }

        fun start(ctx: Context, lat: Double, lon: Double) {
            val i = Intent(ctx, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, Prefs.MODE_STATIC)
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LON, lon)
            }
            ctx.startForegroundService(i)
        }

        /** Runs the saved trip; the position then follows the clock rather than a fixed point. */
        fun startTrip(ctx: Context) {
            val i = Intent(ctx, MockLocationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_MODE, Prefs.MODE_TRIP)
            }
            ctx.startForegroundService(i)
        }

        fun update(ctx: Context, lat: Double, lon: Double) {
            val i = Intent(ctx, MockLocationService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_LAT, lat)
                putExtra(EXTRA_LON, lon)
            }
            ctx.startService(i)
        }

        fun stop(ctx: Context) {
            val i = Intent(ctx, MockLocationService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }

    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var lat = 0.0
    @Volatile private var lon = 0.0
    @Volatile private var tripSource: TripSource? = null
    @Volatile private var phaseLabel: String? = null
    private var lastNotifUpdate = 0L
    private val registered = mutableSetOf<String>()

    private val tick = object : Runnable {
        override fun run() {
            pushFix()
            handler?.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setActive(this, false)
                stopMocking()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                broadcastStatus()
                return START_NOT_STICKY
            }

            ACTION_UPDATE -> {
                // Panning the map must not hijack a running trip.
                if (tripSource == null) {
                    lat = intent.getDoubleExtra(EXTRA_LAT, lat)
                    lon = intent.getDoubleExtra(EXTRA_LON, lon)
                    Prefs.saveTarget(this, lat, lon)
                    notifyForeground()
                    pushFix()
                }
            }

            else -> {
                // ACTION_START, or a null intent because the system restarted us (START_STICKY).
                val mode = intent?.getStringExtra(EXTRA_MODE) ?: Prefs.mode(this)
                Prefs.setMode(this, mode)

                if (mode == Prefs.MODE_TRIP) {
                    val trip = Trip.load(this)
                    if (trip == null) {
                        lastError = getString(R.string.err_no_trip)
                        Prefs.setActive(this, false)
                        broadcastStatus()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    tripSource = TripSource(trip)
                } else {
                    tripSource = null
                    lat = intent?.getDoubleExtra(EXTRA_LAT, Prefs.lat(this)) ?: Prefs.lat(this)
                    lon = intent?.getDoubleExtra(EXTRA_LON, Prefs.lon(this)) ?: Prefs.lon(this)
                    Prefs.saveTarget(this, lat, lon)
                }

                Prefs.setActive(this, true)
                startMocking()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopMocking()
        super.onDestroy()
    }

    // -- mocking ------------------------------------------------------------------------------

    private fun startMocking() {
        notifyForeground()

        lastError = null
        if (!registerProviders()) {
            // Nothing to run. The activity reads lastError, so shut down instead of
            // sitting in the foreground with a dead session.
            Prefs.setActive(this, false)
            isRunning = false
            broadcastStatus()
            Notifier.mockStopped(this, lastError ?: getString(R.string.err_unknown))
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        Notifier.clear(this, Notifier.ID_STOPPED)
        Notifier.clear(this, Notifier.ID_TRIP_DUE)

        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsMock::loop").apply {
                setReferenceCounted(false)
                acquire()
            }
        }

        if (thread == null) {
            thread = HandlerThread("gpsmock-loop").also { it.start() }
            handler = Handler(thread!!.looper)
        }
        handler?.removeCallbacks(tick)
        handler?.post(tick)

        isRunning = true
        broadcastStatus()
        notifyForeground()
    }

    private fun stopMocking() {
        handler?.removeCallbacks(tick)
        thread?.quitSafely()
        thread = null
        handler = null

        unregisterProviders()

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        tripSource = null
        phaseLabel = null
        isRunning = false
    }

    private fun registerProviders(): Boolean {
        var any = false
        for (p in PROVIDERS) {
            if (addProvider(p)) any = true
        }
        if (!any && lastError == null) {
            lastError = getString(R.string.err_unknown)
        }
        return any
    }

    private fun addProvider(provider: String): Boolean {
        return try {
            runCatching { locationManager.removeTestProvider(provider) }
            @Suppress("DEPRECATION")
            locationManager.addTestProvider(
                provider,
                /* requiresNetwork = */ false,
                /* requiresSatellite = */ false,
                /* requiresCell = */ false,
                /* hasMonetaryCost = */ false,
                /* supportsAltitude = */ true,
                /* supportsSpeed = */ true,
                /* supportsBearing = */ true,
                android.location.Criteria.POWER_LOW,
                android.location.Criteria.ACCURACY_FINE
            )
            locationManager.setTestProviderEnabled(provider, true)
            registered.add(provider)
            true
        } catch (e: SecurityException) {
            // The app is not selected under Developer options -> "Select mock location app".
            lastError = getString(R.string.err_not_mock_app)
            Log.w(TAG, "no mock permission for $provider", e)
            false
        } catch (e: IllegalArgumentException) {
            // Some ROMs refuse to mock the network or fused provider. Not fatal on its own.
            Log.w(TAG, "provider $provider rejected", e)
            false
        }
    }

    private fun unregisterProviders() {
        for (p in registered.toList()) {
            runCatching { locationManager.setTestProviderEnabled(p, false) }
            runCatching { locationManager.removeTestProvider(p) }
        }
        registered.clear()
    }

    private fun pushFix() {
        val jitterOn = Prefs.jitter(this)
        val accuracy = Prefs.accuracy(this)
        val now = System.currentTimeMillis()

        var bearing = Random.nextDouble(0.0, 360.0).toFloat()
        var speed = 0f
        val snapshotLat: Double
        val snapshotLon: Double

        val source = tripSource
        if (source != null) {
            val fix = source.fixAt(now)
            if (fix.phase == Phase.FINISHED) {
                // One-off trip is over. Nothing left to report.
                Prefs.setActive(this, false)
                stopMocking()
                broadcastStatus()
                Notifier.tripFinished(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            snapshotLat = fix.lat
            snapshotLon = fix.lon
            bearing = fix.bearing
            speed = fix.speed
            lat = fix.lat
            lon = fix.lon

            val label = describe(fix)
            if (label != phaseLabel || now - lastNotifUpdate > 15_000) {
                phaseLabel = label
                lastNotifUpdate = now
                notifyForeground()
            }
        } else {
            snapshotLat = lat
            snapshotLon = lon
        }

        for (p in registered.toList()) {
            val loc = buildLocation(p, snapshotLat, snapshotLon, jitterOn, accuracy, bearing, speed)
            try {
                locationManager.setTestProviderLocation(p, loc)
            } catch (e: IllegalArgumentException) {
                // The system dropped our test provider. Re-register instead of dying silently.
                Log.w(TAG, "provider $p lost, re-registering", e)
                registered.remove(p)
                if (addProvider(p)) {
                    runCatching { locationManager.setTestProviderLocation(p, loc) }
                }
            } catch (e: SecurityException) {
                lastError = getString(R.string.err_not_mock_app)
                Log.w(TAG, "lost mock permission", e)
                stopMocking()
                Prefs.setActive(this, false)
                broadcastStatus()
                Notifier.permissionLost(this)
                notifyForeground()
                return
            }
        }
    }

    private fun buildLocation(
        provider: String,
        lat: Double,
        lon: Double,
        jitterOn: Boolean,
        accuracy: Float,
        bearingDeg: Float,
        speedMps: Float
    ): Location {
        val wobble = if (jitterOn) JITTER_DEG else 0.0
        return Location(provider).apply {
            latitude = lat + Random.nextDouble(-wobble, wobble)
            longitude = lon + Random.nextDouble(-wobble, wobble)
            altitude = 42.0 + Random.nextDouble(-0.4, 0.4)
            this.accuracy = accuracy + Random.nextDouble(-0.4, 0.4).toFloat()
            bearing = bearingDeg
            speed = speedMps
            // Consumers treat a fix without a fresh monotonic timestamp as stale and drop it.
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            bearingAccuracyDegrees = 5f
            speedAccuracyMetersPerSecond = 0.3f
            verticalAccuracyMeters = 3f
        }
    }

    private fun describe(fix: Fix): String = when (fix.phase) {
        Phase.OUTBOUND -> getString(
            R.string.phase_outbound, (fix.progress * 100).toInt(), (fix.speed * 3.6f).toInt()
        )

        Phase.INBOUND -> getString(
            R.string.phase_inbound, (fix.progress * 100).toInt(), (fix.speed * 3.6f).toInt()
        )

        Phase.AT_DESTINATION -> getString(R.string.phase_at_destination)
        Phase.AT_START -> getString(R.string.phase_at_start)
        Phase.FINISHED -> getString(R.string.phase_finished)
    }

    // -- notification -------------------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            setShowBadge(false)
            description = getString(R.string.channel_desc)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, MockLocationService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val text = lastError ?: phaseLabel ?: getString(R.string.notif_text, lat, lon)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(if (lastError != null) R.string.notif_error else R.string.notif_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun notifyForeground() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun broadcastStatus() {
        LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(BROADCAST_STATUS))
    }
}
