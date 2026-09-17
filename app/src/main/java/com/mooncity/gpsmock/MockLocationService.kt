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
import com.mooncity.gpsmock.route.Geo
import com.mooncity.gpsmock.trip.Fix
import com.mooncity.gpsmock.trip.Phase
import com.mooncity.gpsmock.trip.Schedule
import com.mooncity.gpsmock.trip.Trip
import com.mooncity.gpsmock.trip.TripAlarms
import com.mooncity.gpsmock.trip.TripSource
import com.mooncity.gpsmock.trip.millisToZoned
import java.time.ZonedDateTime
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

        /** Re-evaluates the schedule right now; sent by the resume alarm. */
        const val ACTION_WAKE = "com.mooncity.gpsmock.action.WAKE"

        /** Broadcast sent whenever the running state or the error state changes. */
        const val BROADCAST_STATUS = "com.mooncity.gpsmock.STATUS"

        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_MODE = "mode"

        private const val CHANNEL_ID = "mock_location"
        private const val NOTIF_ID = 4711

        /** How often a fresh fix is injected. Snapchat and friends discard stale fixes. */
        private const val UPDATE_INTERVAL_MS = 900L

        /**
         * How often a paused session re-checks the schedule. Only a backstop: the resume
         * alarm is what gets us going on time, this catches a dropped or throttled alarm.
         */
        private const val IDLE_INTERVAL_MS = 30_000L

        /**
         * Wie oft zurückgelesen wird, was das System tatsächlich herausgibt. Selten genug,
         * um nicht ins Gewicht zu fallen, oft genug, um einen Aussetzer in wenigen Minuten
         * zu bemerken.
         */
        private const val VERIFY_INTERVAL_MS = 60_000L

        /** ~1.2 m of wobble, so the position is not suspiciously frozen. */
        private const val JITTER_DEG = 0.000011

        /** True while the service is up, whether or not it is currently mocking. */
        @Volatile
        var isRunning = false
            private set

        /**
         * True while a trip session is parked outside its window. The test providers are
         * unregistered in that state, so the phone reports its real position again.
         */
        @Volatile
        var isPaused = false
            private set

        /** When the paused session comes back, or 0 if nothing is scheduled. */
        @Volatile
        var resumeAtMillis = 0L
            private set

        /** True only while fixes are actually being injected. */
        val isMocking: Boolean get() = isRunning && !isPaused

        /**
         * Gesetzt, solange das System etwas anderes herausgibt, als hier gesetzt wird.
         * Kein Fehler im Sinne von [lastError]: die App läuft weiter und versucht es
         * weiter, nur wirkt es gerade nicht.
         */
        @Volatile
        var isOverridden = false
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

        /**
         * Asks a running session to look at the clock again, used when a paused window is
         * due to open. Only legal because the foreground service is already up, which is
         * what exempts us from the background start restriction.
         */
        fun wake(ctx: Context) {
            if (!isRunning) return
            val i = Intent(ctx, MockLocationService::class.java).apply { action = ACTION_WAKE }
            runCatching { ctx.startService(i) }.recoverCatching { ctx.startForegroundService(i) }
        }
    }

    private lateinit var locationManager: LocationManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    @Volatile private var lat = 0.0
    @Volatile private var lon = 0.0
    @Volatile private var trip: Trip? = null
    @Volatile private var tripSource: TripSource? = null
    @Volatile private var phaseLabel: String? = null
    private var lastNotifUpdate = 0L
    private var lastVerify = 0L
    private val drift = DriftMonitor()
    private val registered = mutableSetOf<String>()

    /**
     * Bumped whenever a fresh loop is posted. A wake-up can land while a pass is already
     * running, where removeCallbacks has nothing to cancel; without this the old pass would
     * requeue itself and two chains would push at once.
     */
    @Volatile private var loopGeneration = 0

    private val tick = object : Runnable {
        override fun run() {
            val gen = loopGeneration
            val next = step()
            if (next >= 0 && gen == loopGeneration) handler?.postDelayed(this, next)
        }
    }

    /** Restarts the loop, replacing whatever pass is queued or in flight. */
    private fun restartLoop(delayMs: Long) {
        loopGeneration++
        handler?.removeCallbacks(tick)
        handler?.postDelayed(tick, delayMs)
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
                    pushFix(null)
                }
            }

            ACTION_WAKE -> {
                if (!isRunning) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // Run the loop now rather than at the end of the idle interval.
                restartLoop(0L)
            }

            else -> {
                // ACTION_START, or a null intent because the system restarted us (START_STICKY).
                val mode = intent?.getStringExtra(EXTRA_MODE) ?: Prefs.mode(this)
                Prefs.setMode(this, mode)

                if (mode == Prefs.MODE_TRIP) {
                    val loaded = Trip.load(this)
                    if (loaded == null) {
                        lastError = getString(R.string.err_no_trip)
                        Prefs.setActive(this, false)
                        broadcastStatus()
                        stopSelf()
                        return START_NOT_STICKY
                    }
                    trip = loaded
                    tripSource = TripSource(loaded)
                } else {
                    trip = null
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
        isPaused = false
        resetVerification()
        Notifier.clear(this, Notifier.ID_STOPPED)
        Notifier.clear(this, Notifier.ID_TRIP_DUE)

        if (thread == null) {
            thread = HandlerThread("gpsmock-loop").also { it.start() }
            handler = Handler(thread!!.looper)
        }

        // Starting outside the trip's window: park straight away rather than taking the
        // providers for a moment just to hand them back on the first tick.
        if (shouldPause()) {
            isRunning = true
            enterPause()
            restartLoop(IDLE_INTERVAL_MS)
            return
        }

        if (!registerProviders()) {
            loopGeneration++
            handler?.removeCallbacks(tick)
            failAndStop()
            return
        }

        acquireWakeLock()
        restartLoop(0L)

        isRunning = true
        broadcastStatus()
        notifyForeground()
    }

    /** Nothing to run. The activity reads lastError, so shut down instead of idling dead. */
    private fun failAndStop() {
        Prefs.setActive(this, false)
        isRunning = false
        isPaused = false
        broadcastStatus()
        Notifier.mockStopped(this, lastError ?: getString(R.string.err_unknown))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopMocking() {
        handler?.removeCallbacks(tick)
        thread?.quitSafely()
        thread = null
        handler = null

        unregisterProviders()
        releaseWakeLock()
        TripAlarms.cancelResume(this)
        resetVerification()

        trip = null
        tripSource = null
        phaseLabel = null
        isPaused = false
        resumeAtMillis = 0L
        isRunning = false
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GpsMock::loop").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    /** Beim Start, beim Pausieren und beim Stoppen gilt das bisher Beobachtete nicht mehr. */
    private fun resetVerification() {
        drift.reset()
        isOverridden = false
        lastVerify = 0L
        Notifier.clear(this, Notifier.ID_OVERRIDDEN)
    }

    // -- the window ---------------------------------------------------------------------------

    private fun shouldPause(): Boolean {
        val source = tripSource ?: return false
        return Prefs.pauseOutsideTrip(this) && source.isIdleAt(System.currentTimeMillis())
    }

    /**
     * One pass of the loop. Returns the delay until the next pass, or a negative value when
     * the loop is over because the service is shutting down.
     */
    private fun step(): Long {
        val source = tripSource
        if (source == null) {
            pushFix(null)
            return UPDATE_INTERVAL_MS
        }

        val fix = source.fixAt(System.currentTimeMillis())

        if (fix.phase == Phase.FINISHED) {
            finishTrip()
            return -1L
        }

        if (fix.idle && Prefs.pauseOutsideTrip(this)) {
            if (!isPaused) enterPause()
            return IDLE_INTERVAL_MS
        }

        if (isPaused) {
            // The window just opened. Come back on the next pass, so the fix is computed
            // against the trip as it is now rather than the one loaded before the pause.
            return if (leavePause()) 0L else -1L
        }

        pushFix(fix)
        return UPDATE_INTERVAL_MS
    }

    /**
     * Hands the providers back for the gap between the return leg and the next departure.
     * The service stays up as the thing that knows when to come back, but without the test
     * providers and without the wake lock the phone reports and behaves as it normally would.
     */
    private fun enterPause() {
        isPaused = true
        phaseLabel = null

        unregisterProviders()
        releaseWakeLock()
        // Pausiert wird nichts gesetzt, also gibt es auch nichts zu überwachen.
        resetVerification()

        val next = trip?.let { Schedule.nextDeparture(it, ZonedDateTime.now(Schedule.zone())) }
        resumeAtMillis = next?.toInstant()?.toEpochMilli() ?: 0L
        if (resumeAtMillis > System.currentTimeMillis()) {
            TripAlarms.armResume(this, resumeAtMillis)
        } else {
            TripAlarms.cancelResume(this)
        }

        notifyForeground()
        broadcastStatus()
        Log.i(TAG, "paused until $resumeAtMillis")
    }

    /** Takes the providers back for a window that has just opened. */
    private fun leavePause(): Boolean {
        TripAlarms.cancelResume(this)
        resumeAtMillis = 0L
        isPaused = false

        // The schedule may have been edited while we were idle.
        Trip.load(this)?.let {
            trip = it
            tripSource = TripSource(it)
        }

        lastError = null
        resetVerification()
        if (!registerProviders()) {
            failAndStop()
            return false
        }

        acquireWakeLock()
        Notifier.clear(this, Notifier.ID_TRIP_DUE)
        notifyForeground()
        broadcastStatus()
        return true
    }

    /** A one-off trip has run its course; nothing is left to report. */
    private fun finishTrip() {
        Prefs.setActive(this, false)
        stopMocking()
        broadcastStatus()
        Notifier.tripFinished(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    /** Injects one position: the trip's [fix], or the fixed target when it is null. */
    private fun pushFix(fix: Fix?) {
        val jitterOn = Prefs.jitter(this)
        val accuracy = Prefs.accuracy(this)
        val now = System.currentTimeMillis()

        var bearing = Random.nextDouble(0.0, 360.0).toFloat()
        var speed = 0f

        if (fix != null) {
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
        }

        // Read once: ACTION_UPDATE can retarget a static session from another thread.
        val snapshotLat = lat
        val snapshotLon = lon

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

        verifyOccasionally(snapshotLat, snapshotLon, now)
    }

    // -- Rückkanal ----------------------------------------------------------------------------

    /**
     * Liest zurück, was das System herausgibt, und vergleicht es mit dem, was gerade gesetzt
     * wurde. Ohne das bliebe ein stilles Überschreiben - eingeschaltete Google-Standort-
     * genauigkeit, eine Hersteller-Eigenheit - unbemerkt, weil dabei keine Exception fliegt.
     */
    private fun verifyOccasionally(expectedLat: Double, expectedLon: Double, now: Long) {
        if (now - lastVerify < VERIFY_INTERVAL_MS) return
        lastVerify = now

        val loc = readBack() ?: return
        val age = (SystemClock.elapsedRealtimeNanos() - loc.elapsedRealtimeNanos) / 1_000_000L
        val mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            @Suppress("DEPRECATION")
            loc.isFromMockProvider
        }
        val distance = Geo.distanceMeters(loc.latitude, loc.longitude, expectedLat, expectedLon)

        val verdict = MockCheck.verdict(age, mocked, distance)
        if (!drift.record(verdict)) return

        isOverridden = drift.isFailing
        if (isOverridden) {
            Log.w(TAG, "override erkannt: mock=$mocked, abstand=${distance.toInt()} m, alter=$age ms")
            Notifier.mockOverridden(this)
        } else {
            Log.i(TAG, "simulation kommt wieder an")
            Notifier.clear(this, Notifier.ID_OVERRIDDEN)
        }
        notifyForeground()
        broadcastStatus()
    }

    /** Die frischeste Position, die das System zu den benutzten Providern kennt. */
    private fun readBack(): Location? = try {
        registered.toList()
            .mapNotNull { runCatching { locationManager.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.elapsedRealtimeNanos }
    } catch (e: SecurityException) {
        null
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

    /** When the paused session comes back, phrased for a notification. */
    private fun nextWindowLabel(): String {
        val at = resumeAtMillis
        if (at <= 0L) return getString(R.string.trip_next_none)
        return Schedule.humanTime(millisToZoned(at))
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

        val text = when {
            lastError != null -> lastError!!
            isPaused -> getString(R.string.notif_paused_text, nextWindowLabel())
            isOverridden -> getString(R.string.notif_overridden_text)
            else -> phaseLabel ?: getString(R.string.notif_text, lat, lon)
        }
        val title = when {
            lastError != null -> R.string.notif_error
            isPaused -> R.string.notif_paused
            isOverridden -> R.string.notif_overridden
            else -> R.string.notif_title
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(title))
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
