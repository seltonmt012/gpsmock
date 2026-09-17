package com.mooncity.gpsmock.route

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Entfernung und Richtung zwischen zwei Koordinaten.
 *
 * Liegt getrennt von den Aufrufern, weil die Fahrtwiedergabe und die Rückkanal-Prüfung
 * dasselbe Ergebnis brauchen: die eine rechnet aus, wo sie hinlaufen soll, die andere
 * prüft, wie weit die gemeldete Position davon abweicht.
 */
object Geo {

    private const val EARTH_R = 6_371_000.0

    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_R * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Kurs von Punkt 1 nach Punkt 2 in Grad, 0 = Norden. */
    fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return ((Math.toDegrees(atan2(y, x)) + 360.0) % 360.0).toFloat()
    }
}
