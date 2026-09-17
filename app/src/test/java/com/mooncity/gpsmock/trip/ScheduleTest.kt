package com.mooncity.gpsmock.trip

import com.mooncity.gpsmock.trip.Fixtures.MONDAY
import com.mooncity.gpsmock.trip.Fixtures.TUESDAY
import com.mooncity.gpsmock.trip.Fixtures.WEDNESDAY
import com.mooncity.gpsmock.trip.Fixtures.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZonedDateTime

/**
 * Der Zeitplan wird von der Wiedergabe, dem Kalender und dem Wecker gemeinsam benutzt -
 * eine Abweichung hier bedeutet, dass die drei sich widersprechen.
 */
class ScheduleTest {

    private fun zoned(date: java.time.LocalDate, time: LocalTime): ZonedDateTime =
        date.atTime(time).atZone(Schedule.zone())

    @Test
    fun `laeuft nur an den gewaehlten Tagen`() {
        val montags = trip(days = setOf(DayOfWeek.MONDAY))

        assertTrue(Schedule.runsOn(montags, MONDAY))
        assertFalse(Schedule.runsOn(montags, TUESDAY))
    }

    @Test
    fun `eine einmalige Fahrt laeuft an keinem Wochentag`() {
        assertFalse(Schedule.runsOn(trip(days = emptySet()), MONDAY))
    }

    @Test
    fun `schiebt die Rueckfahrt einer Nachtschicht auf den Folgetag`() {
        val nachts = trip(outTime = LocalTime.of(22, 0), returnTime = LocalTime.of(6, 0))

        val occ = Schedule.occurrenceOn(nachts, MONDAY)

        assertEquals(MONDAY, occ.outStart.toLocalDate())
        assertEquals("Rückfahrt um 06:00 kann nicht vor der Abfahrt um 22:00 liegen",
            TUESDAY, occ.returnStart.toLocalDate())
        assertTrue(occ.returnStart.isAfter(occ.outStart))
    }

    @Test
    fun `nimmt die juengste vergangene Abfahrt`() {
        val montags = trip(days = setOf(DayOfWeek.MONDAY))

        val occ = Schedule.currentOccurrence(montags, zoned(WEDNESDAY, LocalTime.of(12, 0)))

        assertEquals("Am Mittwoch ist der Montag die letzte Abfahrt", MONDAY, occ?.date)
    }

    @Test
    fun `ignoriert eine Abfahrt die noch bevorsteht`() {
        val taeglich = trip()

        val occ = Schedule.currentOccurrence(taeglich, zoned(TUESDAY, LocalTime.of(6, 0)))

        assertEquals("Um 06:00 ist die Abfahrt von heute 07:00 noch nicht dran",
            MONDAY, occ?.date)
    }

    @Test
    fun `findet die naechste Abfahrt am selben Tag`() {
        val next = Schedule.nextDeparture(trip(), zoned(MONDAY, LocalTime.of(6, 0)))

        assertEquals(zoned(MONDAY, LocalTime.of(7, 0)), next)
    }

    @Test
    fun `ueberspringt Tage ohne Fahrt`() {
        val montags = trip(days = setOf(DayOfWeek.MONDAY))

        val next = Schedule.nextDeparture(montags, zoned(MONDAY, LocalTime.of(12, 0)))

        assertEquals(zoned(MONDAY.plusWeeks(1), LocalTime.of(7, 0)), next)
    }

    @Test
    fun `hat nach einer abgelaufenen einmaligen Fahrt keine naechste Abfahrt`() {
        val einmalig = trip(
            days = emptySet(),
            createdAtMillis = Fixtures.at(MONDAY, LocalTime.of(8, 0))
        )

        assertNull(Schedule.nextDeparture(einmalig, zoned(WEDNESDAY, LocalTime.of(12, 0))))
    }

    @Test
    fun `liefert die naechsten Fahrten in zeitlicher Reihenfolge`() {
        val werktags = trip(
            days = setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
            )
        )

        val kommende = Schedule.upcoming(werktags, MONDAY, 6)

        assertEquals(6, kommende.size)
        assertEquals(MONDAY, kommende.first().date)
        // Fünf Werktage, dann erst wieder der Montag darauf.
        assertEquals(MONDAY.plusWeeks(1), kommende.last().date)
        assertTrue(kommende.zipWithNext().all { (a, b) -> a.date < b.date })
    }

    // -- Beschriftungen ------------------------------------------------------------------

    @Test
    fun `nennt heute nur die Uhrzeit`() {
        val jetzt = zoned(MONDAY, LocalTime.of(20, 0))

        assertEquals("06:00", Schedule.humanTime(zoned(MONDAY, LocalTime.of(6, 0)), jetzt))
    }

    @Test
    fun `nennt morgen beim Namen`() {
        val jetzt = zoned(MONDAY, LocalTime.of(20, 0))

        assertEquals("morgen 06:00", Schedule.humanTime(zoned(TUESDAY, LocalTime.of(6, 0)), jetzt))
    }

    @Test
    fun `nennt spaetere Tage mit Wochentag`() {
        val jetzt = zoned(MONDAY, LocalTime.of(20, 0))

        assertEquals("Mi 06:00", Schedule.humanTime(zoned(WEDNESDAY, LocalTime.of(6, 0)), jetzt))
    }

    @Test
    fun `fasst gaengige Wochentagsmengen zusammen`() {
        assertEquals("Einmalig", Schedule.label(emptySet()))
        assertEquals("Täglich", Schedule.label(DayOfWeek.entries.toSet()))
        assertEquals(
            "Werktags",
            Schedule.label(
                setOf(
                    DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
                )
            )
        )
        assertEquals("Wochenende", Schedule.label(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))
        assertEquals("Mo, Mi", Schedule.label(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))
    }
}
