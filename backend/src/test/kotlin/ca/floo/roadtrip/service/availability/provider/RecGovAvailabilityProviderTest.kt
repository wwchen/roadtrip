package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.api.availabilityDatesFromObservations
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovAvailabilityProvider
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import ca.floo.roadtrip.clients.recgov.Campsite as RecGovCampsite

class RecGovAvailabilityProviderTest {
    @Test
    fun `catalog availability narrows fetched campground data to linked reservables`() =
        runBlocking {
            val client =
                fakeRecgovClient { campgroundId, _ ->
                    assertEquals("232447", campgroundId)
                    mapOf(
                        "330257" to
                            RecGovCampsite(
                                id = "330257",
                                site = "A12",
                                loop = "A",
                                campsiteType = "STANDARD",
                                maxNumPeople = 6,
                                equipmentTypes = emptyList(),
                                availabilities = mapOf("2026-07-01" to "Available"),
                            ),
                        "330258" to
                            RecGovCampsite(
                                id = "330258",
                                site = "B01",
                                loop = "B",
                                campsiteType = "TENT ONLY",
                                maxNumPeople = 6,
                                equipmentTypes = emptyList(),
                                availabilities = mapOf("2026-07-01" to "Available"),
                            ),
                    )
                }
            val adapter = RecGovAvailabilityProvider(client, enabled = true)

            val batch =
                adapter.catalogAvailability(
                    ref = ProviderRef.RecGov("232447"),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(
                                campsiteId = 330257,
                                vendorId = "330257",
                            ),
                        ),
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-02"),
                )

            val observation = batch.observations.single()
            assertEquals(330257, observation.campsiteId)
            assertEquals(LocalDate.parse("2026-07-01"), observation.date)
            assertEquals(AvailabilityStatus.AVAILABLE, observation.status)
        }

    @Test
    fun `catalog availability preserves caller campsite id when vendor id is a recgov alias`() =
        runBlocking {
            val client =
                fakeRecgovClient { campgroundId, _ ->
                    assertEquals("232447", campgroundId)
                    mapOf(
                        "100" to
                            RecGovCampsite(
                                id = "100",
                                site = "A12",
                                loop = "A",
                                campsiteType = "STANDARD",
                                maxNumPeople = 6,
                                equipmentTypes = emptyList(),
                                availabilities = mapOf("2026-07-01" to "Available"),
                            ),
                    )
                }
            val adapter = RecGovAvailabilityProvider(client, enabled = true)

            val batch =
                adapter.catalogAvailability(
                    ref = ProviderRef.RecGov("232447"),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(
                                campsiteId = 1000,
                                vendorId = "100",
                            ),
                        ),
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-02"),
                )

            val observation = batch.observations.single()
            assertEquals(1000, observation.campsiteId)
            assertEquals(AvailabilityStatus.AVAILABLE, observation.status)
        }

    @Test
    fun `available dates returns per-day facts without requiring a same-site stay`() =
        runBlocking {
            val client =
                fakeRecgovClient { campgroundId, _ ->
                    assertEquals("232447", campgroundId)
                    mapOf(
                        "330257" to
                            RecGovCampsite(
                                id = "330257",
                                site = "A12",
                                loop = "A",
                                campsiteType = "STANDARD",
                                maxNumPeople = 6,
                                equipmentTypes = emptyList(),
                                availabilities =
                                    mapOf(
                                        "2026-07-01" to "Available",
                                        "2026-07-02" to "Reserved",
                                    ),
                            ),
                        "330258" to
                            RecGovCampsite(
                                id = "330258",
                                site = "B01",
                                loop = "B",
                                campsiteType = "STANDARD",
                                maxNumPeople = 6,
                                equipmentTypes = emptyList(),
                                availabilities =
                                    mapOf(
                                        "2026-07-01" to "Reserved",
                                        "2026-07-02" to "Available",
                                    ),
                            ),
                    )
                }
            val adapter = RecGovAvailabilityProvider(client, enabled = true)

            val batch =
                adapter.catalogAvailability(
                    ref = ProviderRef.RecGov("232447"),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(campsiteId = 330257, vendorId = "330257"),
                            CatalogCampsiteRef(campsiteId = 330258, vendorId = "330258"),
                        ),
                    startDate = LocalDate.parse("2026-07-01"),
                    endDate = LocalDate.parse("2026-07-03"),
                )

            val dates = availabilityDatesFromObservations(batch)
            assertEquals(listOf("2026-07-01", "2026-07-02"), dates)
        }

    @Test
    fun `booking url points at the rec_gov campsite page for the single night`() {
        val adapter = RecGovAvailabilityProvider(fakeRecgovClient { _, _ -> emptyMap() }, enabled = true)
        val campsite =
            CampsiteAvailabilityTarget(
                id = 1,
                vendor = "recgov",
                vendorId = "330257",
                name = null,
                loop = null,
                siteType = null,
                raw = null,
            )

        val url = adapter.bookingUrl(campsite, ProviderRef.RecGov("232447"), LocalDate.parse("2026-07-01"))

        assertEquals(
            "https://www.recreation.gov/camping/campsites/330257?startDate=2026-07-01&endDate=2026-07-02",
            url,
        )
    }
}

private fun fakeRecgovClient(
    fetcher: suspend (
        campgroundId: String,
        monthStart: String,
    ) -> Map<String, RecGovCampsite>,
): RecGovAvailabilityClient =
    object : RecGovAvailabilityClient {
        override suspend fun fetchMonth(
            campgroundId: String,
            monthStart: String,
        ): Map<String, RecGovCampsite> = fetcher(campgroundId, monthStart)
    }
