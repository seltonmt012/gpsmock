package com.mooncity.gpsmock.route

import com.mooncity.gpsmock.trip.PolylineEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Der Decoder verarbeitet, was OSRM liefert - hier gegen einen unabhängig geschriebenen
 * Encoder geprüft, damit die Testdaten der Fahrt-Tests nachweislich stimmen.
 */
class PolylineTest {

    @Test
    fun `dekodiert was der Encoder erzeugt hat`() {
        val punkte = listOf(
            doubleArrayOf(52.520008, 13.404954),
            doubleArrayOf(52.516275, 13.377704),
            doubleArrayOf(52.500000, 13.400000),
            doubleArrayOf(52.600000, 13.500000)
        )

        val zurueck = Polyline.decode(PolylineEncoder.encode(punkte))

        assertEquals(punkte.size, zurueck.size)
        punkte.forEachIndexed { i, p ->
            assertEquals("Breitengrad $i", p[0], zurueck[i][0], 0.0000005)
            assertEquals("Längengrad $i", p[1], zurueck[i][1], 0.0000005)
        }
    }

    @Test
    fun `kommt mit negativen Koordinaten zurecht`() {
        val punkte = listOf(
            doubleArrayOf(-33.868820, 151.209290),
            doubleArrayOf(-34.000000, -58.400000)
        )

        val zurueck = Polyline.decode(PolylineEncoder.encode(punkte))

        assertEquals(punkte[1][0], zurueck[1][0], 0.0000005)
        assertEquals(punkte[1][1], zurueck[1][1], 0.0000005)
    }

    @Test
    fun `liefert bei leerer Eingabe eine leere Liste`() {
        assertTrue(Polyline.decode("").isEmpty())
    }
}
