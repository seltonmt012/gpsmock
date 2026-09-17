package com.mooncity.gpsmock.trip

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** One concrete there-and-back run on a specific date. */
data class Occurrence(
    val date: LocalDate,
    val outStart: ZonedDateTime,
    val outEnd: ZonedDateTime,
    val returnStart: ZonedDateTime,
    val returnEnd: ZonedDateTime
)

/**
 * Turns a [Trip]'s weekday selection into concrete departures.
 *
 * Shared by the playback engine, the calendar screen and the alarm scheduler so all three
 * agree on when a trip actually runs.
 */
object Schedule {

    /** How far back to look for a departure that is still in progress. */
    private const val LOOKBACK_DAYS = 8L

    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun zone(): ZoneId = ZoneId.systemDefault()

    fun runsOn(trip: Trip, date: LocalDate): Boolean =
        trip.days.isNotEmpty() && date.dayOfWeek in trip.days

    /** Builds the occurrence for a date, assuming the trip runs that day. */
    fun occurrenceOn(trip: Trip, date: LocalDate): Occurrence {
        val z = zone()
        val outStart = date.atTime(trip.outTime).atZone(z)
        val outEnd = outStart.plusSeconds(trip.outbound.durationSeconds.toLong().coerceAtLeast(60))

        // The return leg is the first occurrence of returnTime strictly after departure,
        // which rolls onto the next day for overnight schedules.
        var retStart = date.atTime(trip.returnTime).atZone(z)
        if (!retStart.isAfter(outStart)) retStart = retStart.plusDays(1)
        val retEnd = retStart.plusSeconds(trip.inbound.durationSeconds.toLong().coerceAtLeast(60))

        return Occurrence(date, outStart, outEnd, retStart, retEnd)
    }

    /**
     * The most recent departure at or before [now], or null when the trip has never run.
     * This is what decides which leg of which day the current position belongs to.
     */
    fun currentOccurrence(trip: Trip, now: ZonedDateTime): Occurrence? {
        if (trip.days.isEmpty()) return onceOccurrence(trip)

        var date = now.toLocalDate()
        for (i in 0..LOOKBACK_DAYS) {
            val d = date.minusDays(i)
            if (!runsOn(trip, d)) continue
            val occ = occurrenceOn(trip, d)
            if (!occ.outStart.isAfter(now)) return occ
        }
        return null
    }

    /** The single run of a one-off trip: the first departure after it was created. */
    fun onceOccurrence(trip: Trip): Occurrence {
        val z = zone()
        val created = java.time.Instant.ofEpochMilli(trip.createdAtMillis).atZone(z)
        var date = created.toLocalDate()
        if (date.atTime(trip.outTime).atZone(z).isBefore(created)) date = date.plusDays(1)
        return occurrenceOn(trip, date)
    }

    /** The next [count] runs starting at [from], for the calendar screen. */
    fun upcoming(trip: Trip, from: LocalDate, count: Int): List<Occurrence> {
        if (trip.days.isEmpty()) {
            val once = onceOccurrence(trip)
            return if (once.date < from) emptyList() else listOf(once)
        }
        val out = ArrayList<Occurrence>(count)
        var d = from
        var guard = 0
        while (out.size < count && guard < 400) {
            if (runsOn(trip, d)) out.add(occurrenceOn(trip, d))
            d = d.plusDays(1)
            guard++
        }
        return out
    }

    /** The next departure strictly after [now], used to arm the auto-start alarm. */
    fun nextDeparture(trip: Trip, now: ZonedDateTime): ZonedDateTime? {
        if (trip.days.isEmpty()) {
            val once = onceOccurrence(trip)
            return once.outStart.takeIf { it.isAfter(now) }
        }
        var d = now.toLocalDate()
        for (i in 0..370) {
            val day = d.plusDays(i.toLong())
            if (!runsOn(trip, day)) continue
            val start = occurrenceOn(trip, day).outStart
            if (start.isAfter(now)) return start
        }
        return null
    }

    /** "06:00", "morgen 06:00" or "Mo 06:00" — whichever is unambiguous seen from [now]. */
    fun humanTime(at: ZonedDateTime, now: ZonedDateTime = ZonedDateTime.now(zone())): String {
        val time = at.format(HHMM)
        val today = now.toLocalDate()
        return when (at.toLocalDate()) {
            today -> time
            today.plusDays(1) -> "morgen $time"
            else -> "${short(at.dayOfWeek)} $time"
        }
    }

    fun label(days: Set<DayOfWeek>): String = when {
        days.isEmpty() -> "Einmalig"
        days.size == 7 -> "Täglich"
        days == setOf(
            DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
            DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
        ) -> "Werktags"

        days == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Wochenende"
        else -> DayOfWeek.entries.filter { it in days }.joinToString(", ") { short(it) }
    }

    fun short(d: DayOfWeek): String = when (d) {
        DayOfWeek.MONDAY -> "Mo"
        DayOfWeek.TUESDAY -> "Di"
        DayOfWeek.WEDNESDAY -> "Mi"
        DayOfWeek.THURSDAY -> "Do"
        DayOfWeek.FRIDAY -> "Fr"
        DayOfWeek.SATURDAY -> "Sa"
        DayOfWeek.SUNDAY -> "So"
    }
}
