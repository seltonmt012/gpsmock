package com.mooncity.gpsmock.route

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoTest {

    @Test
    fun `misst eine bekannte Strecke`() {
        // Brandenburger Tor -> Potsdamer Platz, rund 1,1 km Luftlinie.
        val d = Geo.distanceMeters(52.516275, 13.377704, 52.509663, 13.376481)

        assertEquals(740.0, d, 40.0)
    }

    @Test
    fun `misst null zwischen identischen Punkten`() {
        assertEquals(0.0, Geo.distanceMeters(52.5, 13.4, 52.5, 13.4), 0.0001)
    }

    @Test
    fun `misst ueber den Nullmeridian hinweg`() {
        val d = Geo.distanceMeters(51.4779, -0.0015, 51.4779, 0.0015)

        assertEquals("0,003 Grad Länge auf dieser Breite sind gut 200 m", 208.0, d, 15.0)
    }

    @Test
    fun `zeigt nach Norden`() {
        assertEquals(0f, Geo.bearingDegrees(52.5, 13.4, 52.6, 13.4), 0.5f)
    }

    @Test
    fun `zeigt nach Osten`() {
        assertEquals(90f, Geo.bearingDegrees(52.5, 13.4, 52.5, 13.5), 0.5f)
    }

    @Test
    fun `zeigt nach Sueden`() {
        assertEquals(180f, Geo.bearingDegrees(52.5, 13.4, 52.4, 13.4), 0.5f)
    }

    @Test
    fun `liefert den Kurs immer im Bereich null bis 360`() {
        val nachWesten = Geo.bearingDegrees(52.5, 13.4, 52.5, 13.3)

        assertEquals(270f, nachWesten, 0.5f)
    }
}
