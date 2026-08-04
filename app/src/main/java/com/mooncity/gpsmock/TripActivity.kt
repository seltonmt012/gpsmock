package com.mooncity.gpsmock

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.mooncity.gpsmock.databinding.ActivityTripBinding
import com.mooncity.gpsmock.route.Osrm
import com.mooncity.gpsmock.route.Route
import com.mooncity.gpsmock.route.TravelMode
import com.mooncity.gpsmock.trip.Trip
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** Sets up the there-and-back trip: endpoints, travel mode, and the daily schedule. */
class TripActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_MAP_LAT = "mapLat"
        const val EXTRA_MAP_LON = "mapLon"
    }

    private lateinit var b: ActivityTripBinding
    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private var startPoint: Place? = null
    private var endPoint: Place? = null
    private var outTime: LocalTime = LocalTime.of(7, 0)
    private var returnTime: LocalTime = LocalTime.of(16, 0)

    private var outboundRoute: Route? = null
    private var inboundRoute: Route? = null

    private var mapLat = 0.0
    private var mapLon = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityTripBinding.inflate(layoutInflater)
        setContentView(b.root)

        // targetSdk 35 draws edge to edge, so the form has to keep clear of the bars itself.
        ViewCompat.setOnApplyWindowInsetsListener(b.content) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.updatePadding(top = bars.top + 20, bottom = bars.bottom + 32)
            insets
        }

        mapLat = intent.getDoubleExtra(EXTRA_MAP_LAT, 0.0)
        mapLon = intent.getDoubleExtra(EXTRA_MAP_LON, 0.0)

        restoreExisting()
        wireUp()
        updateTimeLabels()
    }

    private fun restoreExisting() {
        val trip = Trip.load(this) ?: return
        startPoint = Place(trip.startName, trip.startLat, trip.startLon)
        endPoint = Place(trip.endName, trip.endLat, trip.endLon)
        outTime = trip.outTime
        returnTime = trip.returnTime
        b.switchDaily.isChecked = trip.repeatDaily
        b.modeGroup.check(
            when (trip.mode) {
                TravelMode.CAR -> R.id.modeCar
                TravelMode.BIKE -> R.id.modeBike
                TravelMode.FOOT -> R.id.modeFoot
            }
        )
        b.startInput.setText(trip.startName)
        b.endInput.setText(trip.endName)
        showResolved()
    }

    private fun wireUp() {
        if (b.modeGroup.checkedButtonId == View.NO_ID) b.modeGroup.check(R.id.modeBike)

        // Changing the profile invalidates whatever was routed before.
        b.modeGroup.addOnButtonCheckedListener { _, _, isChecked -> if (isChecked) invalidateRoute() }

        b.startFromMap.setOnClickListener {
            startPoint = Place(getString(R.string.trip_from_map), mapLat, mapLon)
            b.startInput.setText("")
            invalidateRoute(); showResolved()
        }
        b.endFromMap.setOnClickListener {
            endPoint = Place(getString(R.string.trip_from_map), mapLat, mapLon)
            b.endInput.setText("")
            invalidateRoute(); showResolved()
        }

        b.startInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) resolve(b.startInput.text.toString()) { startPoint = it }
        }
        b.endInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) resolve(b.endInput.text.toString()) { endPoint = it }
        }

        b.btnOutTime.setOnClickListener { pickTime(outTime) { outTime = it; updateTimeLabels(); invalidateRoute() } }
        b.btnReturnTime.setOnClickListener { pickTime(returnTime) { returnTime = it; updateTimeLabels(); invalidateRoute() } }
        b.switchDaily.setOnCheckedChangeListener { _, _ -> }

        b.btnRoute.setOnClickListener { calculateRoute() }
        b.btnSaveStart.setOnClickListener { activate(instant = false) }
        b.btnTestNow.setOnClickListener { activate(instant = true) }
    }

    // -- inputs -------------------------------------------------------------------------------

    private fun selectedMode(): TravelMode = when (b.modeGroup.checkedButtonId) {
        R.id.modeCar -> TravelMode.CAR
        R.id.modeFoot -> TravelMode.FOOT
        else -> TravelMode.BIKE
    }

    private fun pickTime(current: LocalTime, onPicked: (LocalTime) -> Unit) {
        TimePickerDialog(
            this,
            { _, h, m -> onPicked(LocalTime.of(h, m)) },
            current.hour, current.minute, true
        ).show()
    }

    private fun updateTimeLabels() {
        b.btnOutTime.text = getString(R.string.trip_out_time, outTime.format(hhmm))
        b.btnReturnTime.text = getString(R.string.trip_return_time, returnTime.format(hhmm))
    }

    private fun resolve(query: String, assign: (Place) -> Unit) {
        val q = query.trim()
        if (q.isEmpty()) return
        lifecycleScope.launch {
            val results = runCatching { Nominatim.search(q) }.getOrDefault(emptyList())
            when {
                results.isEmpty() -> toast(getString(R.string.search_none))
                results.size == 1 -> {
                    assign(results.first()); invalidateRoute(); showResolved()
                }

                else -> AlertDialog.Builder(this@TripActivity)
                    .setTitle(R.string.search_pick)
                    .setItems(results.map { it.name }.toTypedArray()) { _, i ->
                        assign(results[i]); invalidateRoute(); showResolved()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
        }
    }

    private fun showResolved() {
        b.startResolved.text = startPoint?.let { fmt(it) } ?: getString(R.string.trip_not_set)
        b.endResolved.text = endPoint?.let { fmt(it) } ?: getString(R.string.trip_not_set)
    }

    private fun fmt(p: Place) = String.format(Locale.US, "%.5f, %.5f  %s", p.lat, p.lon, p.name)

    private fun invalidateRoute() {
        outboundRoute = null
        inboundRoute = null
        b.routeSummary.visibility = View.GONE
        b.btnSaveStart.isEnabled = false
        b.btnTestNow.isEnabled = false
    }

    // -- routing ------------------------------------------------------------------------------

    private fun calculateRoute() {
        val s = startPoint
        val e = endPoint
        if (s == null || e == null) {
            toast(getString(R.string.trip_need_points))
            return
        }

        b.busy.visibility = View.VISIBLE
        b.btnRoute.isEnabled = false

        lifecycleScope.launch {
            val mode = selectedMode()
            val out = Osrm.route(mode, s.lat, s.lon, e.lat, e.lon)
            // Routed separately rather than reversed, because one-ways differ per direction.
            val back = if (out != null) Osrm.route(mode, e.lat, e.lon, s.lat, s.lon) else null

            b.busy.visibility = View.GONE
            b.btnRoute.isEnabled = true

            if (out == null || back == null) {
                toast(getString(R.string.trip_route_failed))
                return@launch
            }

            outboundRoute = out
            inboundRoute = back

            b.routeSummary.text = getString(
                R.string.trip_summary,
                distance(out.distanceMeters), duration(out.durationSeconds),
                distance(back.distanceMeters), duration(back.durationSeconds),
                outTime.plusSeconds(out.durationSeconds.toLong()).format(hhmm),
                returnTime.plusSeconds(back.durationSeconds.toLong()).format(hhmm)
            )
            b.routeSummary.visibility = View.VISIBLE
            b.btnSaveStart.isEnabled = true
            b.btnTestNow.isEnabled = true
        }
    }

    private fun distance(m: Double) =
        if (m >= 1000) String.format(Locale.GERMANY, "%.1f km", m / 1000) else "${m.roundToInt()} m"

    private fun duration(s: Double): String {
        val total = s.roundToInt()
        val h = total / 3600
        val min = (total % 3600) / 60
        return if (h > 0) "$h h $min min" else "$min min"
    }

    // -- activation ---------------------------------------------------------------------------

    private fun activate(instant: Boolean) {
        val s = startPoint ?: return
        val e = endPoint ?: return
        val out = outboundRoute ?: return
        val back = inboundRoute ?: return

        val trip = Trip(
            mode = selectedMode(),
            startLat = s.lat, startLon = s.lon, startName = s.name,
            endLat = e.lat, endLon = e.lon, endName = e.name,
            outTime = outTime,
            returnTime = returnTime,
            repeatDaily = b.switchDaily.isChecked,
            outbound = out,
            inbound = back,
            createdAtMillis = System.currentTimeMillis(),
            instantStartMillis = if (instant) System.currentTimeMillis() else null
        )

        Trip.save(this, trip)
        Prefs.setMode(this, Prefs.MODE_TRIP)
        MockLocationService.startTrip(this)
        toast(getString(R.string.trip_saved))
        finish()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
