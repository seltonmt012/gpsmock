package com.mooncity.gpsmock.trip

import android.content.Context
import com.mooncity.gpsmock.route.Route
import com.mooncity.gpsmock.route.TravelMode
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * A there-and-back journey on a weekly schedule: leave at [outTime], come back at
 * [returnTime], on the weekdays in [days]. An empty [days] means the trip runs once.
 *
 * Both legs are routed separately rather than reversing one geometry, because one-way
 * streets make the way home a different path.
 */
data class Trip(
    val mode: TravelMode,
    val startLat: Double,
    val startLon: Double,
    val startName: String,
    val endLat: Double,
    val endLon: Double,
    val endName: String,
    val outTime: LocalTime,
    val returnTime: LocalTime,
    val days: Set<DayOfWeek>,
    val outbound: Route,
    val inbound: Route,
    val createdAtMillis: Long,
    /** Set for a one-off test run that starts the moment the user presses go. */
    val instantStartMillis: Long? = null
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.name)
        put("startLat", startLat)
        put("startLon", startLon)
        put("startName", startName)
        put("endLat", endLat)
        put("endLon", endLon)
        put("endName", endName)
        put("outTime", outTime.toString())
        put("returnTime", returnTime.toString())
        put("days", JSONArray().apply { days.sorted().forEach { put(it.value) } })
        put("outPolyline", outbound.polyline)
        put("outDistance", outbound.distanceMeters)
        put("outDuration", outbound.durationSeconds)
        put("inPolyline", inbound.polyline)
        put("inDistance", inbound.distanceMeters)
        put("inDuration", inbound.durationSeconds)
        put("createdAt", createdAtMillis)
        instantStartMillis?.let { put("instantStart", it) }
    }

    companion object {

        private fun file(ctx: Context) = File(ctx.applicationContext.filesDir, "trip.json")

        fun save(ctx: Context, trip: Trip) {
            file(ctx).writeText(trip.toJson().toString())
        }

        fun load(ctx: Context): Trip? {
            val f = file(ctx)
            if (!f.exists()) return null
            return try {
                val o = JSONObject(f.readText())
                Trip(
                    mode = TravelMode.from(o.optString("mode")),
                    startLat = o.getDouble("startLat"),
                    startLon = o.getDouble("startLon"),
                    startName = o.optString("startName"),
                    endLat = o.getDouble("endLat"),
                    endLon = o.getDouble("endLon"),
                    endName = o.optString("endName"),
                    outTime = LocalTime.parse(o.getString("outTime")),
                    returnTime = LocalTime.parse(o.getString("returnTime")),
                    days = readDays(o),
                    outbound = Route(
                        o.getString("outPolyline"),
                        o.optDouble("outDistance", 0.0),
                        o.optDouble("outDuration", 0.0)
                    ),
                    inbound = Route(
                        o.getString("inPolyline"),
                        o.optDouble("inDistance", 0.0),
                        o.optDouble("inDuration", 0.0)
                    ),
                    createdAtMillis = o.optLong("createdAt", 0L),
                    instantStartMillis = if (o.has("instantStart")) o.getLong("instantStart") else null
                )
            } catch (e: Exception) {
                null
            }
        }

        /** Reads the weekday set, falling back to the boolean flag written by 1.2. */
        private fun readDays(o: JSONObject): Set<DayOfWeek> {
            o.optJSONArray("days")?.let { arr ->
                val set = mutableSetOf<DayOfWeek>()
                for (i in 0 until arr.length()) {
                    runCatching { set.add(DayOfWeek.of(arr.getInt(i))) }
                }
                return set
            }
            return if (o.optBoolean("repeatDaily", true)) DayOfWeek.entries.toSet() else emptySet()
        }

        fun clear(ctx: Context) {
            file(ctx).delete()
        }
    }
}
