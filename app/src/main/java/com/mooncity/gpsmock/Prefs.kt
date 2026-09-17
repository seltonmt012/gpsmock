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
    private const val KEY_AUTOSTART = "autoStart"
    private const val KEY_PAUSE_OUTSIDE = "pauseOutside"
    private const val KEY_REAL_LAT = "realLat"
    private const val KEY_REAL_LON = "realLon"
    private const val KEY_REAL_AT = "realAt"

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

    /** Whether a scheduled departure may start the service on its own. */
    fun autoStart(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_AUTOSTART, false)
    fun setAutoStart(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_AUTOSTART, v).apply()

    /**
     * Whether the trip mode hands the providers back between the return leg and the next
     * departure. Off means the start point is broadcast around the clock, which is what
     * every version before 1.5 did.
     */
    fun pauseOutsideTrip(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_PAUSE_OUTSIDE, true)
    fun setPauseOutsideTrip(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean(KEY_PAUSE_OUTSIDE, v).apply()

    /** Last position read from the real GPS, kept because mocking hides it afterwards. */
    fun saveRealFix(ctx: Context, lat: Double, lon: Double, atMillis: Long) {
        sp(ctx).edit()
            .putLong(KEY_REAL_LAT, java.lang.Double.doubleToRawLongBits(lat))
            .putLong(KEY_REAL_LON, java.lang.Double.doubleToRawLongBits(lon))
            .putLong(KEY_REAL_AT, atMillis)
            .apply()
    }

    fun realFix(ctx: Context): Triple<Double, Double, Long>? {
        val at = sp(ctx).getLong(KEY_REAL_AT, 0L)
        if (at == 0L) return null
        return Triple(
            java.lang.Double.longBitsToDouble(sp(ctx).getLong(KEY_REAL_LAT, 0L)),
            java.lang.Double.longBitsToDouble(sp(ctx).getLong(KEY_REAL_LON, 0L)),
            at
        )
    }

    /** Reported horizontal accuracy in metres. Lower looks like a strong GPS fix. */
    fun accuracy(ctx: Context): Float = sp(ctx).getFloat(KEY_ACCURACY, 4f)
    fun setAccuracy(ctx: Context, v: Float) = sp(ctx).edit().putFloat(KEY_ACCURACY, v).apply()

    /** Whether to wobble the position by ~1 m, like a real receiver does. */
    fun jitter(ctx: Context): Boolean = sp(ctx).getBoolean(KEY_JITTER, true)
    fun setJitter(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_JITTER, v).apply()
}
