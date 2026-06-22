package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.clients.cache.CachedRecGovAvailability
import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.api.availabilityDatesFromObservations
import ca.floo.roadtrip.service.reservation.adapters.recgov.RecGovReservationProvider
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecGovReservationProviderTest {
    @Test
    fun `catalog availability narrows cached campground data to linked reservables`() =
        runBlocking {
            val cache =
                CachedRecGovAvailability(
                    fetchMonth = { campgroundId, _ ->
                        assertEquals("232447", campgroundId)
                        mapOf(
                            "330257" to
                                Campsite(
                                    id = "330257",
                                    site = "A12",
                                    loop = "A",
                                    campsiteType = "STANDARD",
                                    maxNumPeople = 6,
                                    equipmentTypes = emptyList(),
                                    availabilities = mapOf("2026-07-01" to "Available"),
                                ),
                            "330258" to
                                Campsite(
                                    id = "330258",
                                    site = "B01",
                                    loop = "B",
                                    campsiteType = "TENT ONLY",
                                    maxNumPeople = 6,
                                    equipmentTypes = emptyList(),
                                    availabilities = mapOf("2026-07-01" to "Available"),
                                ),
                        )
                    },
                )
            val adapter = RecGovReservationProvider(cache)

            val batch =
                adapter.catalogAvailability(
                    CatalogAvailabilityRequest(
                        ref = ProviderRef.RecGov("232447"),
                        reservables =
                            listOf(
                                CatalogReservableRef(
                                    rid = "site:recgov:330257",
                                    vendorId = "330257",
                                ),
                            ),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                    ),
                )

            val observation = batch.observations.single()
            assertEquals("site:recgov:330257", observation.reservableId)
            assertEquals(LocalDate.parse("2026-07-01"), observation.date)
            assertEquals(AvailabilityStatus.AVAILABLE, observation.status)
        }

    @Test
    fun `reservable availability narrows cached campground data to one campsite`() =
        runBlocking {
            val cache =
                CachedRecGovAvailability(
                    fetchMonth = { campgroundId, _ ->
                        assertEquals("232447", campgroundId)
                        mapOf(
                            "330257" to
                                Campsite(
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
                            "999999" to
                                Campsite(
                                    id = "999999",
                                    site = "B01",
                                    loop = "B",
                                    campsiteType = "STANDARD",
                                    maxNumPeople = 6,
                                    equipmentTypes = emptyList(),
                                    availabilities = mapOf("2026-07-01" to "Available"),
                                ),
                        )
                    },
                )
            val adapter = RecGovReservationProvider(cache)

            val batch =
                adapter.reservableAvailability(
                    ReservableAvailabilityRequest(
                        ref = ProviderRef.RecGov("232447"),
                        vendorId = "330257",
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                    ),
                )

            assertEquals("site:recgov:330257", batch.reservableId)
            assertEquals("site:recgov:330257", batch.observations.single().reservableId)
            assertEquals(AvailabilityStatus.AVAILABLE, batch.observations.single().status)
        }

    @Test
    fun `available dates returns per-day facts without requiring a same-site stay`() =
        runBlocking {
            val cache =
                CachedRecGovAvailability(
                    fetchMonth = { campgroundId, _ ->
                        assertEquals("232447", campgroundId)
                        mapOf(
                            "330257" to
                                Campsite(
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
                                Campsite(
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
                    },
                )
            val adapter = RecGovReservationProvider(cache)

            val batch =
                adapter.availability(
                    AvailabilityRequest(
                        ref = ProviderRef.RecGov("232447"),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-03"),
                    ),
                )

            val dates = availabilityDatesFromObservations(batch)
            assertEquals(listOf("2026-07-01", "2026-07-02"), dates)
        }
}
