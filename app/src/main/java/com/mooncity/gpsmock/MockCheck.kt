package com.mooncity.gpsmock

/**
 * Beurteilt, ob die Simulation noch ankommt.
 *
 * Bisher fiel nur auf, dass etwas kaputt ist, wenn `setTestProviderLocation` eine Exception
 * wirft. Schaltet aber jemand die Google-Standortgenauigkeit ein oder funkt der Hersteller
 * dazwischen, wird die gesetzte Position stillschweigend überschrieben: die App meldet
 * weiter fleißig, und nach außen steht trotzdem die echte Position. Deshalb wird
 * zurückgelesen, was das System tatsächlich herausgibt.
 *
 * Bewusst ohne Android-Abhängigkeiten, damit die Regel testbar bleibt; das Auslesen der
 * Position macht der Dienst.
 */
object MockCheck {

    /** Älteres verrät nichts mehr über den aktuellen Zustand. */
    const val MAX_AGE_MS = 20_000L

    /**
     * Ab hier ist es keine Ungenauigkeit mehr, sondern eine andere Position. Großzügig
     * gewählt, weil eine laufende Fahrt zwischen Setzen und Zurücklesen ein Stück
     * weiterkommt.
     */
    const val MAX_DRIFT_M = 250.0

    enum class Verdict {
        /** Zurückgelesen wurde, was gesetzt wurde. */
        OK,

        /** Keine brauchbare Antwort - sagt nichts über den Zustand aus. */
        INCONCLUSIVE,

        /** Das System gibt etwas anderes heraus, als die App setzt. */
        OVERRIDDEN
    }

    fun verdict(ageMillis: Long, isMock: Boolean, distanceMeters: Double): Verdict = when {
        // Negative Alter kommen vor, wenn Uhren auseinanderlaufen; auch das sagt nichts.
        ageMillis < 0 || ageMillis > MAX_AGE_MS -> Verdict.INCONCLUSIVE

        // Das deutlichste Signal: die Position stammt gar nicht von uns.
        !isMock -> Verdict.OVERRIDDEN

        distanceMeters > MAX_DRIFT_M -> Verdict.OVERRIDDEN

        else -> Verdict.OK
    }
}

/**
 * Zählt, wie oft hintereinander die Simulation nicht ankam.
 *
 * Ein einzelner Ausreißer bedeutet nichts - ein Provider kann kurz hinterherhinken, und
 * eine Warnung bei jedem Zucken würde niemand ernst nehmen. Gemeldet wird erst, wenn es
 * mehrfach in Folge danebengeht, und zwar genau einmal pro Aussetzer.
 */
class DriftMonitor(private val strikesNeeded: Int = 3) {

    private var strikes = 0
    var isFailing = false
        private set

    /**
     * Verarbeitet ein Prüfergebnis und liefert true, wenn sich der Zustand gerade geändert
     * hat - also entweder die Schwelle erstmals gerissen oder die Simulation sich erholt
     * hat. Nur dann lohnt sich eine Meldung.
     */
    fun record(verdict: MockCheck.Verdict): Boolean {
        when (verdict) {
            MockCheck.Verdict.INCONCLUSIVE -> return false

            MockCheck.Verdict.OK -> {
                strikes = 0
                if (isFailing) {
                    isFailing = false
                    return true
                }
                return false
            }

            MockCheck.Verdict.OVERRIDDEN -> {
                if (isFailing) return false
                strikes++
                if (strikes >= strikesNeeded) {
                    isFailing = true
                    return true
                }
                return false
            }
        }
    }

    fun reset() {
        strikes = 0
        isFailing = false
    }
}
