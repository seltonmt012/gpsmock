package com.mooncity.gpsmock.trip

import com.mooncity.gpsmock.trip.Fixtures.MONDAY
import com.mooncity.gpsmock.trip.Fixtures.TUESDAY
import com.mooncity.gpsmock.trip.Fixtures.WEDNESDAY
import com.mooncity.gpsmock.trip.Fixtures.at
import com.mooncity.gpsmock.trip.Fixtures.trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalTime

/**
 * Deckt ab, wo die Simulation zu welcher Uhrzeit steht - und vor allem, wann sie sich
 * für unzuständig erklärt, damit der Dienst die echten Provider freigibt.
 */
class TripSourceTest {

    // Standardfahrt: täglich, 07:00 los (30 min), 16:00 zurück (30 min).
    private val source = TripSource(trip())

    @Test
    fun `steht zur Abfahrt am Startpunkt`() {
        val fix = source.fixAt(at(MONDAY, LocalTime.of(7, 0)))

        assertEquals(Phase.OUTBOUND, fix.phase)
        assertFalse(fix.idle)
        assertEquals(Fixtures.START_LAT, fix.lat, 0.0005)
        assertEquals(Fixtures.START_LON, fix.lon, 0.0005)
    }

    @Test
    fun `ist zur Halbzeit der Hinfahrt auf halber Strecke`() {
        val fix = source.fixAt(at(MONDAY, LocalTime.of(7, 15)))

        assertEquals(Phase.OUTBOUND, fix.phase)
        assertEquals(0.5f, fix.progress, 0.02f)
        assertEquals((Fixtures.START_LAT + Fixtures.END_LAT) / 2, fix.lat, 0.005)
        assertTrue("Während der Fahrt muss eine Geschwindigkeit anliegen", fix.speed > 0f)
    }

    @Test
    fun `steht nach der Ankunft am Ziel`() {
        val fix = source.fixAt(at(MONDAY, LocalTime.of(7, 45)))

        assertEquals(Phase.AT_DESTINATION, fix.phase)
        assertEquals(Fixtures.END_LAT, fix.lat, 0.0001)
        assertEquals(Fixtures.END_LON, fix.lon, 0.0001)
    }

    /**
     * Der Kern der Pause-Regel: zwischen Ankunft und Rückfahrt wird weiter simuliert.
     * Würde hier pausiert, käme mitten am Arbeitstag die echte Position durch.
     */
    @Test
    fun `pausiert nicht waehrend der Zeit am Ziel`() {
        val fix = source.fixAt(at(MONDAY, LocalTime.of(12, 0)))

        assertEquals(Phase.AT_DESTINATION, fix.phase)
        assertFalse("Innerhalb des Fensters darf nicht pausiert werden", fix.idle)
    }

    @Test
    fun `faehrt zur Rueckfahrtszeit zurueck`() {
        val fix = source.fixAt(at(MONDAY, LocalTime.of(16, 15)))

        assertEquals(Phase.INBOUND, fix.phase)
        assertFalse(fix.idle)
        assertEquals(0.5f, fix.progress, 0.02f)
    }

    @Test
    fun `pausiert nach der Rueckkehr`() {
        val fix = source.fixAt(at(MONDAY, LocalTime.of(16, 45)))

        assertEquals(Phase.AT_START, fix.phase)
        assertTrue("Nach der Rückfahrt muss das Fenster zu sein", fix.idle)
    }

    @Test
    fun `pausiert nachts und morgens vor der Abfahrt`() {
        assertTrue(source.isIdleAt(at(MONDAY, LocalTime.of(23, 0))))
        assertTrue(source.isIdleAt(at(TUESDAY, LocalTime.of(6, 0))))
    }

    @Test
    fun `pausiert an einem Tag ohne Fahrt`() {
        val montagsFahrt = TripSource(trip(days = setOf(DayOfWeek.MONDAY)))

        assertTrue(
            "Mittwochs faehrt diese Fahrt nicht",
            montagsFahrt.isIdleAt(at(WEDNESDAY, LocalTime.of(12, 0)))
        )
    }

    // -- Fahrt über Mitternacht ----------------------------------------------------------

    @Test
    fun `haelt eine Nachtschicht durchgehend am Ziel`() {
        val nachts = TripSource(trip(outTime = LocalTime.of(22, 0), returnTime = LocalTime.of(6, 0)))

        val fix = nachts.fixAt(at(TUESDAY, LocalTime.of(3, 0)))

        assertEquals(Phase.AT_DESTINATION, fix.phase)
        assertFalse("Über Mitternacht läuft das Fenster weiter", fix.idle)
    }

    @Test
    fun `pausiert nach dem Ende der Nachtschicht`() {
        val nachts = TripSource(trip(outTime = LocalTime.of(22, 0), returnTime = LocalTime.of(6, 0)))

        assertEquals(Phase.INBOUND, nachts.fixAt(at(TUESDAY, LocalTime.of(6, 15))).phase)
        assertTrue(nachts.isIdleAt(at(TUESDAY, LocalTime.of(9, 0))))
    }

    // -- einmalige Fahrt -----------------------------------------------------------------

    @Test
    fun `pausiert vor der Abfahrt einer einmaligen Fahrt`() {
        // Montag 08:00 angelegt, Abfahrt 07:00 - das ist erst Dienstag.
        val einmalig = TripSource(
            trip(days = emptySet(), createdAtMillis = at(MONDAY, LocalTime.of(8, 0)))
        )

        assertTrue(einmalig.isIdleAt(at(MONDAY, LocalTime.of(12, 0))))
        assertEquals(Phase.OUTBOUND, einmalig.fixAt(at(TUESDAY, LocalTime.of(7, 15))).phase)
    }

    @Test
    fun `meldet eine einmalige Fahrt nach der Rueckkehr als beendet`() {
        val einmalig = TripSource(
            trip(days = emptySet(), createdAtMillis = at(MONDAY, LocalTime.of(8, 0)))
        )

        val fix = einmalig.fixAt(at(TUESDAY, LocalTime.of(17, 0)))

        assertEquals(Phase.FINISHED, fix.phase)
    }

    // -- Sofort-Test ---------------------------------------------------------------------

    @Test
    fun `startet eine Sofortfahrt ab dem Startzeitpunkt`() {
        val start = at(WEDNESDAY, LocalTime.of(13, 0))
        val sofort = TripSource(trip(instantStartMillis = start))

        assertEquals(Phase.OUTBOUND, sofort.fixAt(start + 60_000).phase)
        assertEquals(Phase.AT_DESTINATION, sofort.fixAt(start + 1800_000 + 60_000).phase)
        assertFalse(sofort.isIdleAt(start + 1800_000 + 60_000))
    }

    // -- Randfälle -----------------------------------------------------------------------

    @Test
    fun `haelt den Fortschritt in den Grenzen`() {
        val fix = source.fixAt(at(MONDAY, LocalTime.of(7, 29)))

        assertTrue(fix.progress in 0f..1f)
    }

    @Test
    fun `liefert bei einer Nullsekunden-Route trotzdem eine Position`() {
        val entartet = TripSource(trip(outSeconds = 0.0, inSeconds = 0.0))

        val fix = entartet.fixAt(at(MONDAY, LocalTime.of(7, 0)))

        assertTrue(fix.lat.isFinite())
        assertTrue(fix.lon.isFinite())
    }
}
