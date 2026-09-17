package com.mooncity.gpsmock.trip

import com.mooncity.gpsmock.route.Geo
import com.mooncity.gpsmock.route.Polyline
import java.time.Instant
import java.time.ZoneId

enum class Phase { OUTBOUND, AT_DESTINATION, INBOUND, AT_START, FINISHED }

data class Fix(
    val lat: Double,
    val lon: Double,
    val bearing: Float,
    val speed: Float,
    val phase: Phase,
    /** 0..1 through the current leg, for the notification. */
    val progress: Float,
    /**
     * True while the trip is outside its window: the return leg is over and the next
     * departure has not come round yet. Nothing is happening, so there is nothing worth
     * simulating and the service may hand the real providers back.
     */
    val idle: Boolean = false
)

/**
 * Turns a [Trip] plus a wall-clock instant into a position.
 *
 * Deliberately a pure function of the clock: nothing is accumulated between ticks, so a
 * killed and restarted process resumes at exactly the right point on the route instead of
 * teleporting back to where it left off.
 */
class TripSource(private val trip: Trip) {

    private val outPts = Polyline.decode(trip.outbound.polyline)
    private val inPts = Polyline.decode(trip.inbound.polyline)
    private val outCum = cumulative(outPts)
    private val inCum = cumulative(inPts)

    private val outDurMs = (trip.outbound.durationSeconds * 1000).toLong().coerceAtLeast(1000L)
    private val inDurMs = (trip.inbound.durationSeconds * 1000).toLong().coerceAtLeast(1000L)

    private val outSpeed = speedOf(outCum.lastOrNull() ?: 0.0, trip.outbound.durationSeconds)
    private val inSpeed = speedOf(inCum.lastOrNull() ?: 0.0, trip.inbound.durationSeconds)

    fun fixAt(nowMillis: Long): Fix {
        trip.instantStartMillis?.let { start ->
            val elapsed = nowMillis - start
            return if (elapsed < outDurMs) {
                travel(outPts, outCum, elapsed.toDouble() / outDurMs, outSpeed, Phase.OUTBOUND)
            } else {
                stay(trip.endLat, trip.endLon, Phase.AT_DESTINATION)
            }
        }

        val zone = ZoneId.systemDefault()
        val now = Instant.ofEpochMilli(nowMillis).atZone(zone)

        // Before the very first departure there is nothing to replay yet.
        val occ = Schedule.currentOccurrence(trip, now)
            ?: return stay(trip.startLat, trip.startLon, Phase.AT_START, idle = true)

        val anchor = occ.outStart.toInstant().toEpochMilli()
        val retStartMs = occ.returnStart.toInstant().toEpochMilli()

        // A one-off trip has nothing after its single return leg.
        if (trip.days.isEmpty() && nowMillis >= retStartMs + inDurMs) {
            return stay(trip.startLat, trip.startLon, Phase.FINISHED)
        }

        // A one-off whose departure is still ahead — a scheduled trip never lands here,
        // because currentOccurrence only hands back departures that have already passed.
        if (nowMillis < anchor) {
            return stay(trip.startLat, trip.startLon, Phase.AT_START, idle = true)
        }

        val outEndMs = anchor + outDurMs
        val retEndMs = retStartMs + inDurMs

        return when {
            nowMillis < outEndMs ->
                travel(outPts, outCum, (nowMillis - anchor).toDouble() / outDurMs, outSpeed, Phase.OUTBOUND)

            nowMillis < retStartMs ->
                stay(trip.endLat, trip.endLon, Phase.AT_DESTINATION)

            nowMillis < retEndMs ->
                travel(inPts, inCum, (nowMillis - retStartMs).toDouble() / inDurMs, inSpeed, Phase.INBOUND)

            // Home again, next departure not due: the window is closed.
            else -> stay(trip.startLat, trip.startLon, Phase.AT_START, idle = true)
        }
    }

    /** Whether [nowMillis] falls outside the trip's active window. */
    fun isIdleAt(nowMillis: Long): Boolean = fixAt(nowMillis).idle

    /** Human-readable leg lengths, for the setup screen. */
    val outboundSummary: Pair<Double, Double>
        get() = trip.outbound.distanceMeters to trip.outbound.durationSeconds

    // -- geometry -----------------------------------------------------------------------------

    private fun stay(lat: Double, lon: Double, phase: Phase, idle: Boolean = false) =
        Fix(lat, lon, 0f, 0f, phase, if (phase == Phase.AT_DESTINATION) 1f else 0f, idle)

    private fun travel(
        pts: List<DoubleArray>,
        cum: DoubleArray,
        fraction: Double,
        speed: Float,
        phase: Phase
    ): Fix {
        if (pts.size < 2) {
            val p = pts.firstOrNull() ?: doubleArrayOf(trip.startLat, trip.startLon)
            return Fix(p[0], p[1], 0f, 0f, phase, fraction.toFloat())
        }

        val f = fraction.coerceIn(0.0, 1.0)
        val target = cum.last() * f

        // Binary search for the segment holding the target distance.
        var lo = 0
        var hi = cum.size - 1
        while (lo < hi - 1) {
            val mid = (lo + hi) / 2
            if (cum[mid] <= target) lo = mid else hi = mid
        }

        val segLen = cum[hi] - cum[lo]
        val t = if (segLen <= 0.0) 0.0 else (target - cum[lo]) / segLen

        val a = pts[lo]
        val b = pts[hi]
        val lat = a[0] + (b[0] - a[0]) * t
        val lon = a[1] + (b[1] - a[1]) * t

        return Fix(lat, lon, Geo.bearingDegrees(a[0], a[1], b[0], b[1]), speed, phase, f.toFloat())
    }

    private fun speedOf(distance: Double, duration: Double): Float =
        if (duration <= 0.0) 0f else (distance / duration).toFloat()

    private fun cumulative(pts: List<DoubleArray>): DoubleArray {
        val out = DoubleArray(pts.size)
        for (i in 1 until pts.size) {
            out[i] = out[i - 1] +
                    Geo.distanceMeters(pts[i - 1][0], pts[i - 1][1], pts[i][0], pts[i][1])
        }
        return out
    }
}
