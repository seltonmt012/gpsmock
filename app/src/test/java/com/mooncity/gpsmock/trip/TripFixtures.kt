package com.mooncity.gpsmock.trip

import com.mooncity.gpsmock.route.Route
import com.mooncity.gpsmock.route.TravelMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Bausteine für die Fahrt-Tests.
 *
 * Alle Zeitpunkte werden über [Schedule.zone] gerechnet statt über eine feste Zone, damit
 * die Tests auf dem CI-Runner (UTC) dasselbe Ergebnis liefern wie auf einem Rechner in
 * Berlin.
 */
object Fixtures {

    const val START_LAT = 52.500000
    const val START_LON = 13.400000
    const val END_LAT = 52.600000
    const val END_LON = 13.500000

    /** Zwei Punkte genügen: die Tests prüfen den Fortschritt, nicht die Straßenführung. */
    val OUT_POLYLINE: String = PolylineEncoder.encode(
        listOf(doubleArrayOf(START_LAT, START_LON), doubleArrayOf(END_LAT, END_LON))
    )

    val IN_POLYLINE: String = PolylineEncoder.encode(
        listOf(doubleArrayOf(END_LAT, END_LON), doubleArrayOf(START_LAT, START_LON))
    )

    fun trip(
        outTime: LocalTime = LocalTime.of(7, 0),
        returnTime: LocalTime = LocalTime.of(16, 0),
        days: Set<DayOfWeek> = DayOfWeek.entries.toSet(),
        outSeconds: Double = 1800.0,
        inSeconds: Double = 1800.0,
        createdAtMillis: Long = 0L,
        instantStartMillis: Long? = null
    ) = Trip(
        mode = TravelMode.CAR,
        startLat = START_LAT,
        startLon = START_LON,
        startName = "Start",
        endLat = END_LAT,
        endLon = END_LON,
        endName = "Ziel",
        outTime = outTime,
        returnTime = returnTime,
        days = days,
        outbound = Route(OUT_POLYLINE, 13_000.0, outSeconds),
        inbound = Route(IN_POLYLINE, 13_000.0, inSeconds),
        createdAtMillis = createdAtMillis,
        instantStartMillis = instantStartMillis
    )

    fun at(date: LocalDate, time: LocalTime): Long =
        date.atTime(time).atZone(Schedule.zone()).toInstant().toEpochMilli()

    /** Ein Montag, damit Wochentags-Tests nicht vom heutigen Datum abhängen. */
    val MONDAY: LocalDate = LocalDate.of(2026, 3, 2)
    val TUESDAY: LocalDate = MONDAY.plusDays(1)
    val WEDNESDAY: LocalDate = MONDAY.plusDays(2)
}

/**
 * Gegenstück zu [com.mooncity.gpsmock.route.Polyline].decode, nur für Testdaten.
 * PolylineRoundTripTest prüft beide gegeneinander, damit ein Fehler hier nicht
 * unbemerkt in die Fahrt-Tests durchschlägt.
 */
object PolylineEncoder {

    fun encode(points: List<DoubleArray>, precision: Int = 6): String {
        val factor = Math.pow(10.0, precision.toDouble())
        val sb = StringBuilder()
        var prevLat = 0L
        var prevLon = 0L

        for (p in points) {
            val lat = Math.round(p[0] * factor)
            val lon = Math.round(p[1] * factor)
            append(lat - prevLat, sb)
            append(lon - prevLon, sb)
            prevLat = lat
            prevLon = lon
        }
        return sb.toString()
    }

    private fun append(delta: Long, sb: StringBuilder) {
        var value = if (delta < 0) (delta shl 1).inv() else (delta shl 1)
        while (value >= 0x20) {
            sb.append((((value and 0x1f) or 0x20).toInt() + 63).toChar())
            value = value shr 5
        }
        sb.append((value.toInt() + 63).toChar())
    }
}
