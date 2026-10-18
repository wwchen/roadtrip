package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.client.aspira.AspiraOccupancy
import ca.floo.roadtrip.client.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ca.floo.roadtrip.client.recgov.Campsite as RecGovCampsite

/**
 * What a call is asked to cover, which is the vendor's own call shape: a vendor
 * that answers a campground per call covers it, and one that answers per part of
 * a campground is not asked to cover parts nobody needs.
 */
class CatalogReachTest {
    @Test
    fun `a vendor that answers per campground part is not asked to cover the rest`() {
        val aspira =
            AspiraAvailabilityProvider(
                tenants = shippedTenantRegistry().tenantsOf(BookingProvider.ASPIRA),
                availabilityClient = unusedAspiraClient(),
                enabled = true,
            )
        val recgov = RecGovAvailabilityProvider(fakeMonthClient(emptyMap()), enabled = true)

        // Aspira fetches per map, so covering a campground costs one call per
        // sibling map; the campground-wide vendors answer the campground whatever
        // they are handed.
        assertFalse(aspira.capabilities.coversCampgroundPerCall)
        assertTrue(recgov.capabilities.coversCampgroundPerCall)
    }

    private fun fakeMonthClient(byVendorId: Map<String, Map<String, String>>): RecGovAvailabilityClient =
        object : RecGovAvailabilityClient {
            override suspend fun fetchMonth(
                campgroundId: String,
                monthStart: String,
            ): Map<String, RecGovCampsite> =
                byVendorId.mapValues { (siteId, availabilities) ->
                    RecGovCampsite(
                        id = siteId,
                        site = siteId,
                        loop = "A",
                        campsiteType = "STANDARD",
                        maxNumPeople = 6,
                        equipmentTypes = emptyList(),
                        availabilities = availabilities,
                    )
                }
        }

    private fun unusedAspiraClient(): AspiraAvailabilityClient =
        object : AspiraAvailabilityClient {
            override suspend fun fetch(
                host: String,
                mapId: Int,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AspiraAvailability = error("capabilities only")

            override suspend fun fetchOccupancy(
                host: String,
                resourceLocationId: Int,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AspiraOccupancy = error("capabilities only")
        }
}
