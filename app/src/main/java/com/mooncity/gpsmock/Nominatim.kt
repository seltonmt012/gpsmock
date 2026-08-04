package com.mooncity.gpsmock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A geocoded location. [primary] is the headline part of the address and [secondary] the
 * rest, so a suggestion row can show a readable two-line entry instead of Nominatim's
 * very long display_name.
 */
data class Place(
    val name: String,
    val lat: Double,
    val lon: Double,
    val primary: String = name.substringBefore(",").trim().ifBlank { name },
    val secondary: String = name.substringAfter(",", "").trim()
)

/** Place lookup and reverse lookup against OpenStreetMap's Nominatim. */
object Nominatim {

    private const val BASE = "https://nominatim.openstreetmap.org"
    private const val USER_AGENT = "GpsMock/1.4 (android; personal use)"

    suspend fun search(query: String, limit: Int = 8): List<Place> = withContext(Dispatchers.IO) {
        val url = URL(
            "$BASE/search?format=jsonv2&addressdetails=1&limit=$limit" +
                    "&q=${URLEncoder.encode(query, "UTF-8")}"
        )
        val body = get(url) ?: return@withContext emptyList()
        try {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i -> parse(arr.optJSONObject(i)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Turns a map pin back into an address, so a picked point gets a readable name. */
    suspend fun reverse(lat: Double, lon: Double): Place? = withContext(Dispatchers.IO) {
        val url = URL("$BASE/reverse?format=jsonv2&addressdetails=1&zoom=18&lat=$lat&lon=$lon")
        val body = get(url) ?: return@withContext null
        try {
            parse(JSONObject(body))?.copy(lat = lat, lon = lon)
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(o: JSONObject?): Place? {
        if (o == null) return null
        val lat = o.optString("lat").toDoubleOrNull() ?: return null
        val lon = o.optString("lon").toDoubleOrNull() ?: return null
        val display = o.optString("display_name")
        if (display.isBlank()) return null

        // Prefer the tagged name over the first address token; it reads better for POIs.
        val head = o.optString("name").takeIf { it.isNotBlank() }
            ?: display.substringBefore(",").trim()
        val tail = display.removePrefix(head).removePrefix(",").trim()

        return Place(name = display, lat = lat, lon = lon, primary = head, secondary = tail)
    }

    private fun get(url: URL): String? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            // Nominatim rejects requests without an identifying agent.
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "de,en")
        }
        return try {
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }
}
