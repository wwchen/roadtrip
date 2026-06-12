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
                        start = LocalDate.parse("2026-07-01"),
                        days = 1,
                        minNights = 2,
                    ),
                )

            assertEquals("site:recgov:330257", dto.reservableId)
            assertEquals("booked", dto.availability.single().status)
            assertEquals(0, dto.availability.single().availableCount)
        }
}
