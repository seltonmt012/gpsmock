package com.mooncity.gpsmock

import com.mooncity.gpsmock.MockCheck.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MockCheckTest {

    @Test
    fun `akzeptiert eine frische Position an der richtigen Stelle`() {
        assertEquals(Verdict.OK, MockCheck.verdict(ageMillis = 900, isMock = true, distanceMeters = 3.0))
    }

    @Test
    fun `erkennt eine echte Position als Uebernahme`() {
        assertEquals(
            "Eine Position, die nicht von uns stammt, ist der klarste Beweis",
            Verdict.OVERRIDDEN,
            MockCheck.verdict(ageMillis = 900, isMock = false, distanceMeters = 0.0)
        )
    }

    @Test
    fun `erkennt eine weit entfernte Position als Uebernahme`() {
        assertEquals(
            Verdict.OVERRIDDEN,
            MockCheck.verdict(ageMillis = 900, isMock = true, distanceMeters = 4_000.0)
        )
    }

    @Test
    fun `wertet eine alte Position nicht`() {
        assertEquals(
            Verdict.INCONCLUSIVE,
            MockCheck.verdict(ageMillis = 120_000, isMock = false, distanceMeters = 9_000.0)
        )
    }

    @Test
    fun `wertet ein negatives Alter nicht`() {
        assertEquals(
            "Auseinanderlaufende Uhren sind kein Beweis für eine Übernahme",
            Verdict.INCONCLUSIVE,
            MockCheck.verdict(ageMillis = -5_000, isMock = false, distanceMeters = 0.0)
        )
    }

    @Test
    fun `laesst eine laufende Fahrt in Ruhe weiterfahren`() {
        // Zwischen Setzen und Zurücklesen kommt ein Auto ein Stück voran.
        assertEquals(
            Verdict.OK,
            MockCheck.verdict(ageMillis = 900, isMock = true, distanceMeters = 40.0)
        )
    }
}

class DriftMonitorTest {

    @Test
    fun `meldet einen einzelnen Ausreisser nicht`() {
        val monitor = DriftMonitor(strikesNeeded = 3)

        assertFalse(monitor.record(Verdict.OVERRIDDEN))
        assertFalse(monitor.isFailing)
    }

    @Test
    fun `meldet nach genug Aussetzern in Folge`() {
        val monitor = DriftMonitor(strikesNeeded = 3)

        assertFalse(monitor.record(Verdict.OVERRIDDEN))
        assertFalse(monitor.record(Verdict.OVERRIDDEN))
        assertTrue("Beim dritten Mal in Folge muss gemeldet werden", monitor.record(Verdict.OVERRIDDEN))
        assertTrue(monitor.isFailing)
    }

    @Test
    fun `meldet denselben Aussetzer nicht zweimal`() {
        val monitor = DriftMonitor(strikesNeeded = 2)
        monitor.record(Verdict.OVERRIDDEN)
        monitor.record(Verdict.OVERRIDDEN)

        assertFalse("Der Zustand hat sich nicht geändert", monitor.record(Verdict.OVERRIDDEN))
        assertFalse(monitor.record(Verdict.OVERRIDDEN))
    }

    @Test
    fun `setzt die Zaehlung nach einem guten Ergebnis zurueck`() {
        val monitor = DriftMonitor(strikesNeeded = 3)
        monitor.record(Verdict.OVERRIDDEN)
        monitor.record(Verdict.OVERRIDDEN)

        monitor.record(Verdict.OK)

        assertFalse("Nach der Erholung zählt es von vorne", monitor.record(Verdict.OVERRIDDEN))
        assertFalse(monitor.isFailing)
    }

    @Test
    fun `meldet die Erholung genau einmal`() {
        val monitor = DriftMonitor(strikesNeeded = 1)
        assertTrue(monitor.record(Verdict.OVERRIDDEN))

        assertTrue("Dass es wieder geht, ist auch eine Meldung wert", monitor.record(Verdict.OK))
        assertFalse(monitor.record(Verdict.OK))
    }

    @Test
    fun `unterbricht eine Serie nicht durch ein unklares Ergebnis`() {
        val monitor = DriftMonitor(strikesNeeded = 2)
        monitor.record(Verdict.OVERRIDDEN)

        assertFalse(monitor.record(Verdict.INCONCLUSIVE))
        assertTrue(
            "Ein unbrauchbares Zwischenergebnis darf die Zählung weder erhöhen noch löschen",
            monitor.record(Verdict.OVERRIDDEN)
        )
    }
}
