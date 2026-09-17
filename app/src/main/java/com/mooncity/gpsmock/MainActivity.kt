package com.mooncity.gpsmock

import android.Manifest
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.mooncity.gpsmock.databinding.ActivityMainBinding
import com.mooncity.gpsmock.update.UpdateChecker
import com.mooncity.gpsmock.update.UpdateInfo
import com.mooncity.gpsmock.route.Polyline
import com.mooncity.gpsmock.trip.Schedule
import com.mooncity.gpsmock.trip.Trip
import com.mooncity.gpsmock.trip.TripAlarms
import com.mooncity.gpsmock.trip.TripSource
import com.mooncity.gpsmock.trip.millisToZoned
import com.mooncity.gpsmock.update.Updater
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.DelayedMapListener
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Overlay
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }

    private lateinit var b: ActivityMainBinding
    private lateinit var searchSuggest: PlaceSuggest

    /** One silent check per app launch; manual checks go through the menu. */
    private var updateCheckedThisLaunch = false

    private val routeOverlays = mutableListOf<Overlay>()
    private val markerOverlays = mutableListOf<Overlay>()

    /** Keeps the mock marker moving along a running trip while the map is on screen. */
    private val markerTicker = object : Runnable {
        override fun run() {
            refreshMarkers()
            maybeRefreshRealFix()
            b.root.postDelayed(this, 2_000)
        }
    }

    /** A fresh-fix request is in flight; a second one would only fight with it. */
    private var locating = false

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = refreshUi()
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshUi()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid needs its config loaded and an identifying agent before any tile is fetched.
        Configuration.getInstance().apply {
            load(this@MainActivity, PreferenceManager.getDefaultSharedPreferences(this@MainActivity))
            userAgentValue = packageName
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        applyInsets()
        setUpMap()
        setUpControls()
        requestPermissions()

        if (intent?.getBooleanExtra(EXTRA_AUTOSTART, false) == true) {
            startMocking()
        }
    }

    override fun onResume() {
        super.onResume()
        b.map.onResume()
        LocalBroadcastManager.getInstance(this)
            .registerReceiver(statusReceiver, IntentFilter(MockLocationService.BROADCAST_STATUS))
        // A force-stop clears pending alarms, so re-arm whenever the app is opened.
        TripAlarms.reschedule(this)

        refreshUi()
        drawTripOverlay()
        refreshMarkers()
        b.root.removeCallbacks(markerTicker)
        b.root.postDelayed(markerTicker, 2_000)

        if (!updateCheckedThisLaunch) {
            updateCheckedThisLaunch = true
            checkForUpdate(silent = true)
            // Open on the user's own position, the way a map app is expected to.
            locateSelf(recenter = !MockLocationService.isMocking)
        }
    }

    override fun onPause() {
        b.root.removeCallbacks(markerTicker)
        LocalBroadcastManager.getInstance(this).unregisterReceiver(statusReceiver)
        val c = b.map.mapCenter
        Prefs.saveTarget(this, c.latitude, c.longitude)
        Prefs.saveZoom(this, b.map.zoomLevelDouble)
        b.map.onPause()
        super.onPause()
    }

    /**
     * targetSdk 35 means Android 15 lays the window out edge to edge, so the cards would sit
     * under the status bar and the gesture bar unless they are inset by hand.
     */
    private fun applyInsets() {
        val gap = (12 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(b.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            b.searchCard.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                topMargin = bars.top + gap
            }
            b.bottomCard.updateLayoutParams<android.view.ViewGroup.MarginLayoutParams> {
                bottomMargin = bars.bottom + gap
            }
            insets
        }
    }

    // -- map ----------------------------------------------------------------------------------

    private fun setUpMap() = with(b.map) {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        minZoomLevel = 2.0
        maxZoomLevel = 19.0

        controller.setZoom(Prefs.zoom(this@MainActivity))
        controller.setCenter(GeoPoint(Prefs.lat(this@MainActivity), Prefs.lon(this@MainActivity)))

        // Debounced, so panning does not spam the service with updates.
        addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                onCenterChanged()
                return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                onCenterChanged()
                return false
            }
        }, 250L))
    }

    private fun onCenterChanged() {
        val c = b.map.mapCenter
        updateCoordsLabel(c.latitude, c.longitude)
        Prefs.saveTarget(this, c.latitude, c.longitude)
        // Moving the map while mocking retargets live, no restart needed.
        if (MockLocationService.isRunning) {
            MockLocationService.update(this, c.latitude, c.longitude)
        }
    }

    private fun updateCoordsLabel(lat: Double, lon: Double) {
        b.tvCoords.text = String.format(Locale.US, "%.6f, %.6f", lat, lon)
    }

    // -- controls -----------------------------------------------------------------------------

    private fun setUpControls() {
        b.btnToggle.setOnClickListener {
            if (MockLocationService.isRunning) stopMocking() else startMocking()
        }

        b.btnSetup.setOnClickListener { showMenu() }

        b.btnTrip.setOnClickListener {
            val c = b.map.mapCenter
            startActivity(
                Intent(this, TripActivity::class.java)
                    .putExtra(TripActivity.EXTRA_MAP_LAT, c.latitude)
                    .putExtra(TripActivity.EXTRA_MAP_LON, c.longitude)
            )
        }

        b.btnSchedule.setOnClickListener { startActivity(Intent(this, ScheduleActivity::class.java)) }

        b.fabLocate.setOnClickListener { locateSelf(recenter = true) }

        searchSuggest = PlaceSuggest(b.searchInput, lifecycleScope) { goTo(it) }
        b.searchBtn.setOnClickListener { runSearch() }
        b.searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                runSearch(); true
            } else false
        }

        updateCoordsLabel(Prefs.lat(this), Prefs.lon(this))
    }

    private fun runSearch() {
        val q = b.searchInput.text.toString().trim()
        if (q.isEmpty()) return

        hideKeyboard()
        b.searchProgress.visibility = android.view.View.VISIBLE

        lifecycleScope.launch {
            val results = runCatching { Nominatim.search(q) }.getOrDefault(emptyList())
            b.searchProgress.visibility = android.view.View.GONE

            when {
                results.isEmpty() -> toast(getString(R.string.search_none))
                results.size == 1 -> goTo(results.first())
                else -> AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.search_pick)
                    .setItems(results.map { it.name }.toTypedArray()) { _, i -> goTo(results[i]) }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun goTo(p: Place) {
        searchSuggest.dismiss()
        hideKeyboard()
        b.map.controller.setZoom(16.0)
        b.map.controller.animateTo(GeoPoint(p.lat, p.lon))
        onCenterChanged()
    }

    // -- start / stop -------------------------------------------------------------------------

    private fun startMocking() {
        if (!hasLocationPermission()) {
            requestPermissions()
            toast(getString(R.string.err_no_location_permission))
            return
        }
        if (!isMockLocationApp()) {
            showMockAppDialog()
            return
        }

        val c = b.map.mapCenter
        MockLocationService.start(this, c.latitude, c.longitude)
        b.root.postDelayed({ refreshUi() }, 600)
        maybeAskBatteryExemption()
    }

    private fun stopMocking() {
        MockLocationService.stop(this)
        b.root.postDelayed({ refreshUi() }, 400)
    }

    private fun refreshUi() {
        val running = MockLocationService.isRunning
        b.btnToggle.setText(if (running) R.string.stop else R.string.start)

        val error = MockLocationService.lastError
        val onTrip = running && Prefs.mode(this) == Prefs.MODE_TRIP
        b.tvStatus.text = when {
            error != null -> error
            running && MockLocationService.isOverridden -> getString(R.string.status_overridden)
            running && MockLocationService.isPaused ->
                getString(R.string.status_trip_paused, nextWindowLabel())

            onTrip -> getString(R.string.status_trip)
            running -> getString(R.string.status_running)
            !isMockLocationApp() -> getString(R.string.err_not_mock_app)
            else -> getString(R.string.status_idle)
        }
    }

    private fun nextWindowLabel(): String {
        val at = MockLocationService.resumeAtMillis
        if (at <= 0L) return getString(R.string.trip_next_none)
        return Schedule.humanTime(millisToZoned(at))
    }

    /** Draws the saved trip's two legs so the schedule is visible on the map. */
    private fun drawTripOverlay() {
        routeOverlays.forEach { b.map.overlays.remove(it) }
        routeOverlays.clear()

        val trip = Trip.load(this) ?: run { b.map.invalidate(); return }

        fun leg(encoded: String, colour: Int) {
            val pts = Polyline.decode(encoded).map { GeoPoint(it[0], it[1]) }
            if (pts.size < 2) return
            val line = org.osmdroid.views.overlay.Polyline(b.map).apply {
                setPoints(pts)
                outlinePaint.color = colour
                outlinePaint.strokeWidth = 10f
            }
            routeOverlays.add(line)
            b.map.overlays.add(0, line)
        }

        leg(trip.outbound.polyline, 0xCC1E88E5.toInt())
        leg(trip.inbound.polyline, 0x99FB8C00.toInt())
        b.map.invalidate()
    }

    // -- system setup -------------------------------------------------------------------------

    /** True once the user picked this app under Developer options -> mock location app. */
    private fun isMockLocationApp(): Boolean {
        val aom = getSystemService(AppOpsManager::class.java) ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                aom.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
            } else {
                @Suppress("DEPRECATION")
                aom.checkOpNoThrow(AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), packageName)
            }
        } catch (e: Exception) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun showMockAppDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_title)
            .setMessage(R.string.setup_mock_msg)
            .setPositiveButton(R.string.open_dev_options) { _, _ -> openDevOptions() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showSetupDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.setup_title)
            .setMessage(R.string.setup_full_msg)
            .setPositiveButton(R.string.open_dev_options) { _, _ -> openDevOptions() }
            .setNeutralButton(R.string.open_battery) { _, _ -> openAppSettings() }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun openDevOptions() {
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        } catch (e: Exception) {
            toast(getString(R.string.err_no_dev_options))
        }
    }

    private fun openAppSettings() {
        startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", packageName, null)
            )
        )
    }

    /**
     * Doze is the main reason mock positions drift back to the real one, so ask for the
     * exemption once the user actually starts a session.
     */
    private fun maybeAskBatteryExemption() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) return

        AlertDialog.Builder(this)
            .setTitle(R.string.battery_title)
            .setMessage(R.string.battery_msg)
            .setPositiveButton(R.string.battery_open) { _, _ ->
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    openAppSettings()
                }
            }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    // -- own position -------------------------------------------------------------------------

    /**
     * Shows where the phone really is.
     *
     * Only possible while nothing is being injected: the test providers replace the real
     * ones system wide, including for this app. During a paused trip they are handed back,
     * so the real position is available again then.
     */
    private fun locateSelf(recenter: Boolean, quiet: Boolean = false) {
        if (!hasLocationPermission()) {
            requestPermissions()
            return
        }

        if (MockLocationService.isMocking) {
            if (!quiet) toast(getString(R.string.real_hidden_while_mocking))
            if (recenter) currentMockPoint()?.let { b.map.controller.animateTo(it) }
            return
        }

        val lm = getSystemService(LocationManager::class.java) ?: return

        lastKnown(lm)?.let { store(it, recenter) }
        requestFreshFix(lm, recenter, quiet)
    }

    /**
     * Keeps the real-position dot current while a trip is parked, which is the whole point
     * of the pause: the phone is reporting its own position again, so the map should too.
     */
    private fun maybeRefreshRealFix() {
        if (locating || !MockLocationService.isPaused || !hasLocationPermission()) return
        val age = Prefs.realFix(this)?.let { System.currentTimeMillis() - it.third } ?: Long.MAX_VALUE
        if (age < 60_000) return
        locateSelf(recenter = false, quiet = true)
    }

    private fun lastKnown(lm: LocationManager): Location? = try {
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
    } catch (e: SecurityException) {
        null
    }

    private fun requestFreshFix(lm: LocationManager, recenter: Boolean, quiet: Boolean = false) {
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                runCatching { lm.removeUpdates(this) }
                locating = false
                store(location, recenter)
            }

            @Deprecated("Required on API < 29")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit

            override fun onProviderDisabled(provider: String) = Unit
            override fun onProviderEnabled(provider: String) = Unit
        }

        try {
            if (!quiet) b.tvStatus.text = getString(R.string.locating)
            locating = true
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, mainLooper)
            lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 0L, 0f, listener, mainLooper)
            // Give up after a while rather than draining the battery on a hopeless fix.
            b.root.postDelayed({
                runCatching { lm.removeUpdates(listener) }
                locating = false
                refreshUi()
            }, 20_000)
        } catch (e: SecurityException) {
            locating = false
            refreshUi()
        }
    }

    private fun store(location: Location, recenter: Boolean) {
        Prefs.saveRealFix(this, location.latitude, location.longitude, System.currentTimeMillis())
        if (recenter) {
            b.map.controller.setZoom(16.5)
            b.map.controller.animateTo(GeoPoint(location.latitude, location.longitude))
        }
        refreshMarkers()
        refreshUi()
    }

    /** Where the mock currently claims to be — computed from the clock for trips. */
    private fun currentMockPoint(): GeoPoint? {
        // Nothing is being injected while paused, so there is no mock position to draw.
        if (!MockLocationService.isMocking) return null
        if (Prefs.mode(this) == Prefs.MODE_TRIP) {
            val trip = Trip.load(this) ?: return null
            val fix = TripSource(trip).fixAt(System.currentTimeMillis())
            return GeoPoint(fix.lat, fix.lon)
        }
        return GeoPoint(Prefs.lat(this), Prefs.lon(this))
    }

    private fun refreshMarkers() {
        markerOverlays.forEach { b.map.overlays.remove(it) }
        markerOverlays.clear()

        val mocking = MockLocationService.isMocking

        Prefs.realFix(this)?.let { (lat, lon, at) ->
            val marker = Marker(b.map).apply {
                position = GeoPoint(lat, lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = ContextCompat.getDrawable(
                    this@MainActivity,
                    if (mocking) R.drawable.ic_dot_stale else R.drawable.ic_dot_real
                )
                title = when {
                    mocking -> getString(R.string.real_position_stale, timeAgo(at))
                    MockLocationService.isPaused -> getString(R.string.real_position_live)
                    else -> getString(R.string.locate)
                }
            }
            markerOverlays.add(marker)
            b.map.overlays.add(marker)
        }

        currentMockPoint()?.let { p ->
            val marker = Marker(b.map).apply {
                position = p
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_mock_pin)
                title = getString(R.string.status_running)
            }
            markerOverlays.add(marker)
            b.map.overlays.add(marker)
        }

        b.map.invalidate()
    }

    private fun timeAgo(at: Long): String {
        val mins = ((System.currentTimeMillis() - at) / 60_000L).toInt()
        return when {
            mins < 1 -> "gerade eben"
            mins < 60 -> "vor $mins min"
            mins < 1440 -> "vor ${mins / 60} h"
            else -> "vor ${mins / 1440} d"
        }
    }

    // -- updates ------------------------------------------------------------------------------

    private fun showMenu() {
        val items = arrayOf(
            getString(R.string.menu_setup),
            getString(R.string.menu_update),
            getString(R.string.menu_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        )
        AlertDialog.Builder(this)
            .setItems(items) { _, i ->
                when (i) {
                    0 -> showSetupDialog()
                    1 -> checkForUpdate(silent = false)
                }
            }
            .show()
    }

    private fun checkForUpdate(silent: Boolean) {
        lifecycleScope.launch {
            val info = UpdateChecker.fetch()
            when {
                info == null -> if (!silent) toast(getString(R.string.update_check_failed))
                !UpdateChecker.isNewer(info) -> if (!silent) toast(getString(R.string.update_none))
                else -> showUpdateDialog(info)
            }
        }
    }

    private fun showUpdateDialog(info: UpdateInfo) {
        val notes = info.notes.ifBlank { getString(R.string.update_no_notes) }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, info.versionName))
            .setMessage(notes)
            .setPositiveButton(R.string.update_now) { _, _ -> beginUpdate(info) }
            .setNegativeButton(R.string.later, null)
            .show()
    }

    private fun beginUpdate(info: UpdateInfo) {
        // Sideloaded apps need the per-app "install unknown apps" grant before they may
        // hand a package to the installer.
        if (!packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(this)
                .setTitle(R.string.update_title_permission)
                .setMessage(R.string.update_permission_msg)
                .setPositiveButton(R.string.battery_open) { _, _ ->
                    runCatching {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:$packageName")
                            )
                        )
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }

        val view = layoutInflater.inflate(R.layout.dialog_progress, null)
        val bar = view.findViewById<android.widget.ProgressBar>(R.id.progressBar)
        val label = view.findViewById<android.widget.TextView>(R.id.progressLabel)
        label.text = getString(R.string.update_downloading)

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_title, info.versionName))
            .setView(view)
            .setCancelable(false)
            .create()
        dialog.show()

        lifecycleScope.launch {
            val result = Updater.downloadAndInstall(this@MainActivity, info) { pct ->
                runOnUiThread {
                    bar.isIndeterminate = false
                    bar.progress = pct
                    label.text = getString(R.string.update_progress, pct)
                }
            }
            dialog.dismiss()
            if (result is Updater.Result.Failed) {
                toast(getString(R.string.update_failed, result.reason))
            }
        }
    }

    // -- misc ---------------------------------------------------------------------------------

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(b.searchInput.windowToken, 0)
        b.searchInput.clearFocus()
    }
}
