package ca.floo.roadtrip.service.booking

import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.campsite.recgov.booker.poller.Campsite
import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.service.booking.adapters.recgov.RecGovBookingProvider
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class RecGovBookingProviderTest {
    @Test
    fun `catalog availability narrows cached campground data to linked reservables`() =
        runBlocking {
            val cache =
                CachedAvailability(
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
            val adapter = RecGovBookingProvider(cache)

            val dto =
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

            val day = dto.availability.single()
            assertEquals(1, day.availableCount)
            assertEquals(1, day.total)
            assertEquals(listOf("site:recgov:330257"), day.availableReservableIds)
        }

    @Test
    fun `reservable availability narrows cached campground data to one campsite`() =
        runBlocking {
            val cache =
                CachedAvailability(
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
            val adapter = RecGovBookingProvider(cache)

            val dto =
                adapter.reservableAvailability(
                    ReservableAvailabilityRequest(
                        ref = ProviderRef.RecGov("232447"),
                        vendorId = "330257",
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-02"),
                    ),
                )

            assertEquals("site:recgov:330257", dto.reservableId)
            assertEquals("available", dto.availability.single().status)
            assertEquals(1, dto.availability.single().availableCount)
        }

    @Test
    fun `available dates returns per-day facts without requiring a same-site stay`() =
        runBlocking {
            val cache =
                CachedAvailability(
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
            val adapter = RecGovBookingProvider(cache)

            val dates =
                adapter.availableDates(
                    AvailableDatesRequest(
                        ref = ProviderRef.RecGov("232447"),
                        startDate = LocalDate.parse("2026-07-01"),
                        endDate = LocalDate.parse("2026-07-03"),
                    ),
                )

            assertEquals(listOf("2026-07-01", "2026-07-02"), dates)
        }
}
