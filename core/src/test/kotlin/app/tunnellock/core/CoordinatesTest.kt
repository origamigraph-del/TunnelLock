package app.tunnellock.core

import app.tunnellock.core.geo.CoordinateParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoordinatesTest {
    private fun parse(text: String) = CoordinateParser.parse(text)

    @Test
    fun barePair() {
        val c = parse("35.6892, 51.3890")!!
        assertEquals(35.6892, c.latitude, 1e-9)
        assertEquals(51.3890, c.longitude, 1e-9)
        assertEquals(parse("35.6892 51.3890"), parse("35.6892;51.3890"))
    }

    @Test
    fun negativeAndWholeNumbers() {
        val c = parse("-33.87, 151")!!
        assertEquals(-33.87, c.latitude, 1e-9)
        assertEquals(151.0, c.longitude, 1e-9)
    }

    @Test
    fun googleMapsLinks() {
        assertEquals(parse("40.7128, -74.006"), parse("https://www.google.com/maps/@40.7128,-74.0060,15z"))
        assertEquals(parse("40.7128, -74.006"), parse("https://maps.google.com/?q=40.7128,-74.0060"))
        assertEquals(parse("40.7128, -74.006"), parse("https://maps.google.com/?q=40.7128%2C-74.0060&z=15"))
    }

    @Test
    fun geoUri() {
        val c = parse("geo:35.6892,51.389?z=16")!!
        assertEquals(35.6892, c.latitude, 1e-9)
    }

    @Test
    fun theZoomIsNotTakenAsCoordinates() {
        // @lat,lon,zoom — the 15z must not become the longitude.
        val c = parse("https://www.google.com/maps/@35.6892,51.389,15z")!!
        assertEquals(51.389, c.longitude, 1e-9)
    }

    @Test
    fun outOfRangeIsRejected() {
        assertNull(parse("95.0, 10.0"))
        assertNull(parse("10.0, 200.0"))
    }

    @Test
    fun junkIsRejected() {
        assertNull(parse(""))
        assertNull(parse("tehran"))
        assertNull(parse("35.6892"))
        // A sentence with one embedded pair is not a bare pair and has no link markers.
        assertNull(parse("somewhere around 35.6, 51.3 maybe"))
    }

    @Test
    fun roundTripThroughFormat() {
        val c = parse("35.6892, 51.389")!!
        assertEquals(c, parse(c.format()))
    }
}
