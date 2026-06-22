package ca.floo.roadtrip.service.availability

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class CoordinateTimeZonesTest {
    @Test
    fun `resolves time zone from coordinates`() {
        assertEquals(ZoneId.of("America/Vancouver"), CoordinateTimeZones.resolve(lat = 49.2827, lng = -123.1207))
        assertEquals(ZoneId.of("America/Edmonton"), CoordinateTimeZones.resolve(lat = 51.1784, lng = -115.5708))
        assertEquals(ZoneId.of("America/Phoenix"), CoordinateTimeZones.resolve(lat = 33.4942, lng = -111.9261))
    }

    @Test
    fun `falls back for missing or invalid coordinates`() {
        assertEquals(ZoneId.of("America/Vancouver"), CoordinateTimeZones.resolve(lat = null, lng = -123.1207))
        assertEquals(ZoneId.of("America/Vancouver"), CoordinateTimeZones.resolve(lat = 49.2827, lng = null))
        assertEquals(ZoneId.of("America/Vancouver"), CoordinateTimeZones.resolve(lat = 91.0, lng = -123.1207))
    }
}
