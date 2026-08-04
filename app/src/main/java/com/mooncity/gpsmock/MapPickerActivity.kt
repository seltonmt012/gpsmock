package com.mooncity.gpsmock

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import com.mooncity.gpsmock.databinding.ActivityMapPickerBinding
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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

/**
 * Full-screen map for choosing a point: pan the crosshair, search by name, or jump to the
 * phone's own position. The address under the crosshair is resolved as the map settles so
 * the choice is confirmed in words, not just coordinates.
 */
class MapPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_NAME = "name"

        fun intent(ctx: Context, title: String, lat: Double?, lon: Double?): Intent =
            Intent(ctx, MapPickerActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                if (lat != null && lon != null) {
                    putExtra(EXTRA_LAT, lat)
                    putExtra(EXTRA_LON, lon)
                }
            }
    }

    private lateinit var b: ActivityMapPickerBinding
    private lateinit var suggest: PlaceSuggest

    private var reverseJob: Job? = null
    private var pickedName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Configuration.getInstance().apply {
            load(this@MapPickerActivity, PreferenceManager.getDefaultSharedPreferences(this@MapPickerActivity))
            userAgentValue = packageName
        }

        b = ActivityMapPickerBinding.inflate(layoutInflater)
        setContentView(b.root)

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

        intent.getStringExtra(EXTRA_TITLE)?.let { b.searchInput.hint = it }

        setUpMap()
        setUpControls()
    }

    override fun onResume() {
        super.onResume()
        b.map.onResume()
    }

    override fun onPause() {
        b.map.onPause()
        super.onPause()
    }

    private fun setUpMap() = with(b.map) {
        setTileSource(TileSourceFactory.MAPNIK)
        setMultiTouchControls(true)
        zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        isTilesScaledToDpi = true
        minZoomLevel = 2.0
        maxZoomLevel = 19.0

        val lat = intent.takeIf { it.hasExtra(EXTRA_LAT) }?.getDoubleExtra(EXTRA_LAT, 0.0)
        val lon = intent.takeIf { it.hasExtra(EXTRA_LON) }?.getDoubleExtra(EXTRA_LON, 0.0)

        when {
            lat != null && lon != null -> {
                controller.setZoom(16.5)
                controller.setCenter(GeoPoint(lat, lon))
            }

            else -> {
                // Nothing preselected: start where the phone actually is if we know it.
                val real = Prefs.realFix(this@MapPickerActivity)
                controller.setZoom(if (real != null) 16.0 else Prefs.zoom(this@MapPickerActivity))
                controller.setCenter(
                    if (real != null) GeoPoint(real.first, real.second)
                    else GeoPoint(Prefs.lat(this@MapPickerActivity), Prefs.lon(this@MapPickerActivity))
                )
            }
        }

        addMapListener(DelayedMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean {
                onCentreMoved(); return false
            }

            override fun onZoom(event: ZoomEvent?): Boolean {
                onCentreMoved(); return false
            }
        }, 300L))

        post { onCentreMoved() }
    }

    private fun setUpControls() {
        suggest = PlaceSuggest(b.searchInput, lifecycleScope) { place ->
            b.map.controller.setZoom(16.5)
            b.map.controller.animateTo(GeoPoint(place.lat, place.lon))
            showAddress(place)
            pickedName = place.primary
        }

        b.fabLocate.setOnClickListener { jumpToSelf() }

        b.btnConfirm.setOnClickListener {
            val c = b.map.mapCenter
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_LAT, c.latitude)
                    .putExtra(EXTRA_LON, c.longitude)
                    .putExtra(EXTRA_NAME, pickedName ?: coordLabel(c.latitude, c.longitude))
            )
            finish()
        }
    }

    /** Resolves the address under the crosshair once panning has settled. */
    private fun onCentreMoved() {
        val c = b.map.mapCenter
        b.coords.text = coordLabel(c.latitude, c.longitude)

        reverseJob?.cancel()
        b.busy.visibility = View.VISIBLE
        reverseJob = lifecycleScope.launch {
            delay(400)
            val place = runCatching { Nominatim.reverse(c.latitude, c.longitude) }.getOrNull()
            b.busy.visibility = View.GONE
            if (place != null) {
                showAddress(place)
                pickedName = place.primary
            } else {
                b.addressPrimary.setText(R.string.picker_unknown)
                b.addressSecondary.text = ""
                pickedName = null
            }
        }
    }

    private fun showAddress(place: Place) {
        b.addressPrimary.text = place.primary
        b.addressSecondary.text = place.secondary
        b.addressSecondary.visibility = if (place.secondary.isBlank()) View.GONE else View.VISIBLE
    }

    private fun jumpToSelf() {
        if (MockLocationService.isRunning) {
            // The test providers hide the real position, so fall back to the last one seen.
            val real = Prefs.realFix(this)
            if (real != null) b.map.controller.animateTo(GeoPoint(real.first, real.second))
            return
        }

        val lm = getSystemService(LocationManager::class.java)
        val fresh = try {
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm?.getLastKnownLocation(it) }.getOrNull() }
                .maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }

        val target = when {
            fresh != null -> {
                Prefs.saveRealFix(this, fresh.latitude, fresh.longitude, System.currentTimeMillis())
                GeoPoint(fresh.latitude, fresh.longitude)
            }

            else -> Prefs.realFix(this)?.let { GeoPoint(it.first, it.second) }
        } ?: return

        b.map.controller.setZoom(16.5)
        b.map.controller.animateTo(target)
    }

    private fun coordLabel(lat: Double, lon: Double) =
        String.format(Locale.US, "%.6f, %.6f", lat, lon)
}
