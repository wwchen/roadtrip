package ca.floo.roadtrip.model.domain.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BookingProviderRefTest {
    @Test
    fun `aspira roundtrips through serialize and parse`() {
        val ref =
            BookingProviderRef.Aspira(
                tenant = "bc",
                transactionLocationId = 4189,
                mapId = -2147483548,
                resourceLocationId = -2147483408,
            )
        val serialized = ref.serialize()
        assertEquals("bc:4189:-2147483548:-2147483408", serialized)
        val parsed = BookingProviderRef.parse(BookingProvider.ASPIRA, serialized)
        assertEquals(ref, parsed)
    }

    @Test
    fun `recgov roundtrips through serialize and parse`() {
        val ref = BookingProviderRef.RecGov(facilityId = "232447")
        assertEquals("232447", ref.serialize())
        assertEquals(ref, BookingProviderRef.parse(BookingProvider.RECGOV, "232447"))
    }

    @Test
    fun `campflare roundtrips through serialize and parse`() {
        val ref = BookingProviderRef.Campflare(campgroundId = "recgov-232447")
        assertEquals("recgov-232447", ref.serialize())
        assertEquals(ref, BookingProviderRef.parse(BookingProvider.CAMPFLARE, "recgov-232447"))
    }

    @Test
    fun `reserve america roundtrips through serialize and parse`() {
        val ref = BookingProviderRef.ReserveAmerica(contractCode = "ABPP", parkId = "330800")
        assertEquals("ABPP:330800", ref.serialize())
        assertEquals(ref, BookingProviderRef.parse(BookingProvider.RESERVEAMERICA, "ABPP:330800"))
    }

    @Test
    fun `reserve california roundtrips through serialize and parse`() {
        val ref = BookingProviderRef.ReserveCalifornia(placeId = 690, facilityIds = listOf(612L, 613L))
        assertEquals("690:612,613", ref.serialize())
        assertEquals(ref, BookingProviderRef.parse(BookingProvider.RESERVECALIFORNIA, "690:612,613"))
    }

    @Test
    fun `aspira parse returns null for malformed ref`() {
        assertNull(BookingProviderRef.parse(BookingProvider.ASPIRA, "bc:4189"))
        assertNull(BookingProviderRef.parse(BookingProvider.ASPIRA, "bc:abc:-2147483548:-2147483408"))
    }

    @Test
    fun `reserve america parse returns null without colon`() {
        assertNull(BookingProviderRef.parse(BookingProvider.RESERVEAMERICA, "330800"))
    }

    @Test
    fun `reserve california parse returns null without colon`() {
        assertNull(BookingProviderRef.parse(BookingProvider.RESERVECALIFORNIA, "690"))
    }
}
