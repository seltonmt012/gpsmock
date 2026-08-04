package com.mooncity.gpsmock

import android.Manifest
import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import com.mooncity.gpsmock.trip.Trip
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
import java.util.Locale

class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }

    private lateinit var b: ActivityMainBinding

    /** One silent check per app launch; manual checks go through the menu. */
    private var updateCheckedThisLaunch = false

    private val routeOverlays = mutableListOf<org.osmdroid.views.overlay.Overlay>()

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
        refreshUi()
        drawTripOverlay()

        if (!updateCheckedThisLaunch) {
            updateCheckedThisLaunch = true
            checkForUpdate(silent = true)
        }
    }

    override fun onPause() {
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
            onTrip -> getString(R.string.status_trip)
            running -> getString(R.string.status_running)
            !isMockLocationApp() -> getString(R.string.err_not_mock_app)
            else -> getString(R.string.status_idle)
        }
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
