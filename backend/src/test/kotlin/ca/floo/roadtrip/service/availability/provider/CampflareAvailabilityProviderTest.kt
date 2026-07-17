package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.model.availability.campflare.CampflareCampgroundAvailability
import ca.floo.roadtrip.model.availability.campflare.CampflareCampsiteAvailability
import ca.floo.roadtrip.model.domain.ProviderRef
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class CampflareAvailabilityProviderTest {
    @Test
    fun `catalog availability uses bulk endpoint and narrows to linked reservables`() =
        runBlocking {
            var observedCall: CampflareCall? = null
            val client =
                CampflareAvailabilityClient { campgroundIds, startDate, endDate ->
                    observedCall = CampflareCall(campgroundIds, startDate, endDate)
                    CampflareAvailability(
                        campgrounds =
                            mapOf(
                                "upper-pines-campground-447" to
                                    CampflareCampgroundAvailability(
                                        campgroundId = "upper-pines-campground-447",
                                        campsiteAvailability =
                                            listOf(
                                                CampflareCampsiteAvailability(
                                                    campsiteId = "upper-pines-site-100",
                                                    availability =
                                                        mapOf(
                                                            LocalDate.parse("2026-06-01") to AvailabilityStatus.AVAILABLE,
                                                            LocalDate.parse("2026-06-02") to AvailabilityStatus.RESERVED,
                                                        ),
                                                ),
                                                CampflareCampsiteAvailability(
                                                    campsiteId = "upper-pines-site-200",
                                                    availability =
                                                        mapOf(
                                                            LocalDate.parse("2026-06-01") to AvailabilityStatus.AVAILABLE,
                                                        ),
                                                ),
                                            ),
                                    ),
                            ),
                        observedAt = Instant.EPOCH,
                    )
                }
            val provider = CampflareAvailabilityProvider(client, enabled = true)

            val batch =
                provider.catalogAvailability(
                    ref = ProviderRef.Campflare("upper-pines-campground-447"),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(campsiteId = 100, vendorId = "upper-pines-site-100"),
                        ),
                    startDate = LocalDate.parse("2026-06-01"),
                    endDate = LocalDate.parse("2026-06-03"),
                )

            assertEquals(AvailabilityProviderId.CAMPFLARE, provider.id)
            assertEquals(true, provider.capabilities.supportsInternalPolling)
            assertEquals(365, provider.capabilities.bookingHorizonDays)
            assertEquals(60, provider.capabilities.maxPollWindowDays)
            assertEquals(
                CampflareCall(
                    campgroundIds = listOf("upper-pines-campground-447"),
                    startDate = LocalDate.parse("2026-06-01"),
                    endDate = LocalDate.parse("2026-06-03"),
                ),
                observedCall,
            )
            assertEquals("campflare", batch.provider)
            assertEquals("upper-pines-campground-447", batch.campgroundId)
            assertEquals(2, batch.observations.size)
            assertEquals(setOf(100L), batch.observations.map { it.campsiteId }.toSet())
            assertEquals(
                listOf(AvailabilityStatus.AVAILABLE, AvailabilityStatus.RESERVED),
                batch.observations.sortedBy { it.date }.map { it.status },
            )
        }

    @Test
    fun `catalog availability fills missing linked campsite dates as unknown`() =
        runBlocking {
            val client =
                CampflareAvailabilityClient { _, _, _ ->
                    CampflareAvailability(
                        campgrounds =
                            mapOf(
                                "upper-pines-campground-447" to
                                    CampflareCampgroundAvailability(
                                        campgroundId = "upper-pines-campground-447",
                                        campsiteAvailability = emptyList(),
                                    ),
                            ),
                        observedAt = Instant.EPOCH,
                    )
                }
            val provider = CampflareAvailabilityProvider(client, enabled = true)

            val batch =
                provider.catalogAvailability(
                    ref = ProviderRef.Campflare("upper-pines-campground-447"),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(campsiteId = 100, vendorId = "upper-pines-site-100"),
                        ),
                    startDate = LocalDate.parse("2026-06-01"),
                    endDate = LocalDate.parse("2026-06-03"),
                )

            assertEquals(listOf(AvailabilityStatus.UNKNOWN, AvailabilityStatus.UNKNOWN), batch.observations.map { it.status })
        }

    private data class CampflareCall(
        val campgroundIds: List<String>,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )
}
