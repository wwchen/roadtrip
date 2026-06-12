package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.service.booking.adapters.aspira.AspiraBookingProvider
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class AspiraBookingProviderTest {
    @Test
    fun `reservable availability maps each tenant to its reservable vendor`() =
        runBlocking {
            val cache =
                CachedAspiraAvailability(
                    fetcher = { _, mapId, _, _ ->
                        AspiraAvailability(
                            mapId = mapId,
                            parkRollup = emptyList(),
                            byMapLink = emptyMap(),
                            byResource = mapOf("-2147478966" to listOf(1)),
                        )
                    },
                )

            val cases =
                listOf(
                    BookingProviderId.ASPIRA_PC to "aspira_pc",
                    BookingProviderId.ASPIRA_BC to "aspira_bc",
                    BookingProviderId.ASPIRA_WA to "aspira_wa",
                )
            for ((providerId, vendor) in cases) {
                val adapter = AspiraBookingProvider(providerId, "example.goaspira.com", cache)
                val dto =
                    adapter.reservableAvailability(
                        ReservableAvailabilityRequest(
                            ref =
                                ProviderRef.Aspira(
                                    transactionLocationId = -2147483648,
                                    mapId = -2147483516,
                                    resourceLocationId = -2147483515,
                                ),
                            vendorId = "-2147478966",
                            start = LocalDate.parse("2026-07-01"),
                            days = 1,
                        ),
                    )

                assertEquals("site:$vendor:-2147478966", dto.reservableId)
                assertEquals("available", dto.availability.single().status)
            }
        }
}
