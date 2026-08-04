package com.mooncity.gpsmock.update

import com.mooncity.gpsmock.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val notes: String
)

/** Reads the update manifest published alongside the APK releases. */
object UpdateChecker {

    suspend fun fetch(): UpdateInfo? = withContext(Dispatchers.IO) {
        // raw.githubusercontent sits behind a CDN with a few minutes of caching,
        // so a cache buster keeps a fresh release from being invisible.
        val url = URL("${BuildConfig.UPDATE_MANIFEST_URL}?cb=${System.currentTimeMillis()}")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Cache-Control", "no-cache")
            setRequestProperty("User-Agent", "GpsMock/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode !in 200..299) return@withContext null
            val o = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val apkUrl = o.optString("apkUrl")
            if (apkUrl.isBlank() || !apkUrl.startsWith("https://")) return@withContext null
            UpdateInfo(
                versionCode = o.optInt("versionCode", -1),
                versionName = o.optString("versionName"),
                apkUrl = apkUrl,
                notes = o.optString("notes")
            )
        } catch (e: Exception) {
            null
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(info: UpdateInfo) = info.versionCode > BuildConfig.VERSION_CODE
}
