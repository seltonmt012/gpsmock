package com.mooncity.gpsmock

import android.content.Context

/** Persisted state, so the service can resume itself after a kill or a reboot. */
object Prefs {

    private const val FILE = "gpsmock_state"
    private const val KEY_LAT = "lat"
    private const val KEY_LON = "lon"
    private const val KEY_ZOOM = "zoom"
    private const val KEY_ACTIVE = "active"
    private const val KEY_ACCURACY = "accuracy"
    private const val KEY_JITTER = "jitter"
    private const val KEY_MODE = "mode"

    const val MODE_STATIC = "static"
    const val MODE_TRIP = "trip"

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun saveTarget(ctx: Context, lat: Double, lon: Double) {
        sp(ctx).edit()
            .putLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_LON, java.lang.Double.doubleToRawLongBits(lon))
            .apply()
    }

    fun lat(ctx: Context): Double =
        java.lang.Double.longBitsToDouble(sp(ctx).getLong(KEY_LAT, java.lang.Double.doubleToRawLongBits(52.520008)))

    fun lon(ctx: Context): Double =
        java.lang.Double.longBitsToDouble(sp(ctx).getLong(KEY_LON, java.lang.Double.doubleToRawLongBits(13.404954)))

    fun saveZoom(ctx: Context, zoom: Double) {
        sp(ctx).edit().putFloat(KEY_ZOOM, zoom.toFloat()).apply()
    }

    fun zoom(ctx: Context): Double = sp(ctx).getFloat(KEY_ZOOM, 5f).toDouble()

    /** True while the user wants mocking on. Survives process death and reboot. */
    fun setActive(ctx: Context, value: Boolean) = sp(ctx).edit().putBoolean(KEY_ACTIVE, value).apply()
    fun isActive(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_ACTIVE, false)

    /** Which position source the service should use: a fixed point or a scheduled trip. */
    fun mode(ctx: Context): String = sp(ctx).getString(KEY_MODE, MODE_STATIC) ?: MODE_STATIC
    fun setMode(ctx: Context, mode: String) = sp(ctx).edit().putString(KEY_MODE, mode).apply()

    /** Reported horizontal accuracy in metres. Lower looks like a strong GPS fix. */
    fun accuracy(ctx: Context): Float = sp(ctx).getFloat(KEY_ACCURACY, 4f)
    fun setAccuracy(ctx: Context, v: Float) = sp(ctx).edit().putFloat(KEY_ACCURACY, v).apply()

    /** Whether to wobble the position by ~1 m, like a real receiver does. */
    fun jitter(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_JITTER, true)
    fun setJitter(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_JITTER, v).apply()
}
