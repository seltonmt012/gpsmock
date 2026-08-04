package com.mooncity.gpsmock.route

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

enum class TravelMode(val profile: String) {
    CAR("routed-car"),
    BIKE("routed-bike"),
    FOOT("routed-foot");

    companion object {
        fun from(name: String?) = entries.firstOrNull { it.name == name } ?: BIKE
    }
}

/** A routed path: the encoded geometry plus how far and how long it is. */
data class Route(
    val polyline: String,
    val distanceMeters: Double,
    val durationSeconds: Double
)

/**
 * Routing against the FOSSGIS OSRM instances that power openstreetmap.org.
 * Free, no key, and it offers car, bike and foot profiles.
 */
object Osrm {

    private const val BASE = "https://routing.openstreetmap.de"
    private const val USER_AGENT = "GpsMock/1.2 (android; personal use)"

    suspend fun route(
        mode: TravelMode,
        fromLat: Double, fromLon: Double,
        toLat: Double, toLon: Double
    ): Route? = withContext(Dispatchers.IO) {
        val url = URL(
            "$BASE/${mode.profile}/route/v1/driving/" +
                    "$fromLon,$fromLat;$toLon,$toLat" +
                    "?overview=full&geometries=polyline6&alternatives=false&steps=false"
        )
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            if (o.optString("code") != "Ok") return@withContext null
            val r = o.optJSONArray("routes")?.optJSONObject(0) ?: return@withContext null
            val geometry = r.optString("geometry")
            if (geometry.isBlank()) return@withContext null
            Route(
                polyline = geometry,
                distanceMeters = r.optDouble("distance", 0.0),
                durationSeconds = r.optDouble("duration", 0.0)
            )
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
