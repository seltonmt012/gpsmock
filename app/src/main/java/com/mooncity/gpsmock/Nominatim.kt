package com.mooncity.gpsmock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Place(val name: String, val lat: Double, val lon: Double)

/** Free-text place lookup against OpenStreetMap's Nominatim. */
object Nominatim {

    private const val ENDPOINT = "https://nominatim.openstreetmap.org/search"
    private const val USER_AGENT = "GpsMock/1.0 (android; personal use)"

    suspend fun search(query: String): List<Place> = withContext(Dispatchers.IO) {
        val url = URL("$ENDPOINT?format=json&limit=8&q=${URLEncoder.encode(query, "UTF-8")}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            // Nominatim rejects requests without an identifying agent.
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept-Language", "de,en")
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val lat = o.optString("lat").toDoubleOrNull() ?: return@mapNotNull null
                val lon = o.optString("lon").toDoubleOrNull() ?: return@mapNotNull null
                Place(o.optString("display_name"), lat, lon)
            }
        } finally {
            conn.disconnect()
        }
    }
}
