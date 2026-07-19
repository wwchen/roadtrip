package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderRefParserTest {
    @Test
    fun `parses recgov`() {
        val ref = ProviderRefParser.parse("""{"recgov_id": "232450"}""")
        assertTrue(ref is BookingProviderRef.RecGov)
        assertEquals("232450", ref.facilityId)
    }

    @Test
    fun `parses campflare`() {
        val ref = ProviderRefParser.parse("""{"campflare_id": "upper-pines-campground-447"}""")
        assertTrue(ref is BookingProviderRef.Campflare)
        assertEquals("upper-pines-campground-447", ref.campgroundId)
    }

    @Test
    fun `parses aspira with all three ids as Long`() {
        val ref =
            ProviderRefParser.parse(
                """{"transactionLocationId": 9876543210, "mapId": 5550000001, "resourceLocationId": 42}""",
            )
        assertTrue(ref is BookingProviderRef.Aspira)
        assertEquals(9876543210L, ref.transactionLocationId)
        assertEquals(5550000001L, ref.mapId)
        assertEquals(42L, ref.resourceLocationId)
    }

    @Test
    fun `parses aspira with null resourceLocationId`() {
        val ref =
            ProviderRefParser.parse(
                """{"transactionLocationId": 100, "mapId": 200, "resourceLocationId": null}""",
            )
        assertTrue(ref is BookingProviderRef.Aspira)
        assertEquals(null, ref.resourceLocationId)
    }

    @Test
    fun `parses reserveamerica`() {
        val ref = ProviderRefParser.parse("""{"contract_code": "NY", "park_id": "489"}""")
        assertTrue(ref is BookingProviderRef.ReserveAmerica)
        assertEquals("NY", ref.contractCode)
        assertEquals("489", ref.parkId)
    }

    @Test
    fun `parses reservecalifornia`() {
        val ref = ProviderRefParser.parse("""{"place_id": 690, "facility_ids": [611, 612, 767]}""")
        assertTrue(ref is BookingProviderRef.ReserveCalifornia)
        assertEquals(690L, ref.placeId)
        assertEquals(listOf(611L, 612L, 767L), ref.facilityIds)
    }

    @Test
    fun `parses numeric legacy facility id as reserveamerica without contract`() {
        val ref = ProviderRefParser.parse("""{"facility_id": "489"}""")
        assertTrue(ref is BookingProviderRef.ReserveAmerica)
        assertEquals(null, ref.contractCode)
        assertEquals("489", ref.parkId)
    }

    @Test
    fun `non numeric legacy facility id is not availability capable`() {
        assertNull(ProviderRefParser.parse("""{"facility_id": "AB-12"}"""))
    }

    @Test
    fun `null on malformed JSON`() {
        assertNull(ProviderRefParser.parse("{not json"))
        assertNull(ProviderRefParser.parse(""))
    }

    @Test
    fun `null on unknown shape`() {
        assertNull(ProviderRefParser.parse("""{"foo": "bar"}"""))
    }

    @Test
    fun `aspira with only mapId returns null`() {
        // The legacy parser accepted this; the writer has always emitted both
        // ids. Strict shape catches data drift early.
        assertNull(ProviderRefParser.parse("""{"mapId": 100}"""))
    }
}
