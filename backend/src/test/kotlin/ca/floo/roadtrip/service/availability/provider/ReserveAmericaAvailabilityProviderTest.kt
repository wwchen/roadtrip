package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveAmericaAvailabilityProviderTest {
    @Test
    fun `catalog availability parses reserveamerica matrix and narrows to requested reservables`() =
        runBlocking {
            val availabilityClient =
                ReserveAmericaAvailabilityClient { host, _, _, startDate, _ ->
                    assertEquals("newyorkstateparks.reserveamerica.com", host)
                    assertEquals(LocalDate.parse("2026-06-22"), startDate)
                    ReserveAmericaAvailability(
                        contractCode = "NY",
                        parkId = "489",
                        startDate = LocalDate.parse("2026-06-22"),
                        endDate = LocalDate.parse("2026-06-24"),
                        observedAt = Instant.parse("2026-06-22T12:00:00Z"),
                        statuses =
                            mapOf(
                                "253481" to
                                    mapOf(
                                        LocalDate.parse("2026-06-22") to AvailabilityStatus.AVAILABLE,
                                        LocalDate.parse("2026-06-23") to AvailabilityStatus.RESERVED,
                                    ),
                                "253488" to
                                    mapOf(
                                        LocalDate.parse("2026-06-22") to AvailabilityStatus.RESERVED,
                                        LocalDate.parse("2026-06-23") to AvailabilityStatus.AVAILABLE,
                                    ),
                            ),
                    )
                }
            val adapter =
                ReserveAmericaAvailabilityProvider(
                    tenants =
                        mapOf(
                            "NY" to
                                ReserveAmericaTenant(
                                    host = "newyorkstateparks.reserveamerica.com",
                                    contractCode = "NY",
                                    bookingHorizonDays = 270,
                                ),
                        ),
                    availabilityClient = availabilityClient,
                    enabled = true,
                )

            val batch =
                adapter.catalogAvailability(
                    campground = testCampground(bookingProvider = "reserveamerica", bookingProviderRef = "NY:489"),
                    campsites =
                        listOf(
                            campsiteFixture(id = 253488, vendor = "reserveamerica", vendorId = "253488"),
                        ),
                    startDate = LocalDate.parse("2026-06-22"),
                    endDate = LocalDate.parse("2026-06-24"),
                )

            assertEquals(BookingProvider.RESERVEAMERICA, adapter.id)
            assertEquals(false, adapter.capabilities.supportsInternalPolling)
            assertEquals("reserveamerica", batch.provider)
            assertEquals("489", batch.campgroundId)
            assertEquals(2, batch.observations.size)
            assertEquals(
                listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.AVAILABLE),
                batch.observations.sortedBy { it.date }.map { it.status },
            )
            assertEquals(setOf(253488L), batch.observations.map { it.campsiteId }.toSet())
        }
}
