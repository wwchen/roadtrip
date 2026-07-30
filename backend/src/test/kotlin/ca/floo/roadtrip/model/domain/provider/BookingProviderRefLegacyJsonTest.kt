package ca.floo.roadtrip.model.domain.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The legacy JSON encoding is a persisted/served shape with no type tag, so the
 * only thing keeping the writer and reader in agreement is that they share one
 * codec. These tests pin the encoding per vendor and prove the round trip.
 */
class BookingProviderRefLegacyJsonTest {
    private val allVendors =
        listOf<BookingProviderRef>(
            BookingProviderRef.RecGov(facilityId = "232447"),
            BookingProviderRef.Campflare(campgroundId = "upper-pines-campground-447"),
            BookingProviderRef.Aspira(
                tenant = null,
                transactionLocationId = 4_294_967_296L,
                mapId = 8_589_934_592L,
                resourceLocationId = 12L,
            ),
            BookingProviderRef.ReserveAmerica(contractCode = "ABPP", parkId = "1234"),
            BookingProviderRef.ReserveCalifornia(placeId = 671L, facilityIds = listOf(1L, 2L, 3L)),
        )

    @Test
    fun `round trips every vendor variant`() {
        assertEquals(
            BookingProvider.entries.toSet(),
            allVendors.map { it.provider }.toSet(),
            "every BookingProvider needs a round-trip case",
        )
        for (ref in allVendors) {
            val json = BookingProviderRefLegacyJson.toLegacyJson(ref)
            assertEquals(ref, BookingProviderRefLegacyJson.fromLegacyJson(json), "round trip for $json")
        }
    }

    @Test
    fun `round trips optional fields left out`() {
        val refs =
            listOf<BookingProviderRef>(
                BookingProviderRef.Aspira(
                    tenant = null,
                    transactionLocationId = 1L,
                    mapId = 2L,
                    resourceLocationId = null,
                ),
                BookingProviderRef.ReserveAmerica(contractCode = null, parkId = "9"),
            )
        for (ref in refs) {
            val json = BookingProviderRefLegacyJson.toLegacyJson(ref)
            assertEquals(ref, BookingProviderRefLegacyJson.fromLegacyJson(json), "round trip for $json")
        }
    }

    @Test
    fun `writes the vendor-keyed shape the API contract promises`() {
        assertEquals(
            """{"recgov_id":"232447"}""",
            BookingProviderRefLegacyJson.toLegacyJson(BookingProviderRef.RecGov("232447")),
        )
        assertEquals(
            """{"campflare_id":"upper-pines"}""",
            BookingProviderRefLegacyJson.toLegacyJson(BookingProviderRef.Campflare("upper-pines")),
        )
        assertEquals(
            """{"transactionLocationId":1,"mapId":2,"resourceLocationId":3}""",
            BookingProviderRefLegacyJson.toLegacyJson(BookingProviderRef.Aspira(null, 1L, 2L, 3L)),
        )
        assertEquals(
            """{"contract_code":"NY","park_id":"77"}""",
            BookingProviderRefLegacyJson.toLegacyJson(BookingProviderRef.ReserveAmerica("NY", "77")),
        )
        assertEquals(
            """{"place_id":671,"facility_ids":[1,2]}""",
            BookingProviderRefLegacyJson.toLegacyJson(BookingProviderRef.ReserveCalifornia(671L, listOf(1L, 2L))),
        )
    }

    @Test
    fun `aspira tenant is not part of the encoding`() {
        val json =
            BookingProviderRefLegacyJson.toLegacyJson(
                BookingProviderRef.Aspira(tenant = "bcparks", transactionLocationId = 1L, mapId = 2L, resourceLocationId = null),
            )
        val parsed = BookingProviderRefLegacyJson.fromLegacyJson(json) as BookingProviderRef.Aspira
        assertNull(parsed.tenant, "tenant is resolved from the host at read time, never persisted here")
    }

    @Test
    fun `reads the legacy facility_id spelling of a ReserveAmerica park`() {
        assertEquals(
            BookingProviderRef.ReserveAmerica(contractCode = null, parkId = "1234"),
            BookingProviderRefLegacyJson.fromLegacyJson("""{"facility_id":"1234"}"""),
        )
    }

    @Test
    fun `unknown and malformed shapes read as no ref`() {
        assertNull(BookingProviderRefLegacyJson.fromLegacyJson("not json"))
        assertNull(BookingProviderRefLegacyJson.fromLegacyJson("[]"))
        assertNull(BookingProviderRefLegacyJson.fromLegacyJson("{}"))
        assertNull(BookingProviderRefLegacyJson.fromLegacyJson("""{"facility_id":"not-a-number"}"""))
        assertNull(BookingProviderRefLegacyJson.fromLegacyJson("""{"place_id":671}"""))
    }
}
