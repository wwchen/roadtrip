package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaTenant
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveAmericaAvailabilityProviderTest {
    @Test
    fun `catalog availability parses reserveamerica matrix and narrows to requested reservables`() =
        runBlocking {
            val client =
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
                    tenant =
                        ReserveAmericaTenant(
                            source = "new-york-state-parks",
                            host = "newyorkstateparks.reserveamerica.com",
                            contractCode = "NY",
                            bookingHorizonDays = 270,
                        ),
                    client = client,
                )

            val batch =
                adapter.catalogAvailability(
                    ref = ProviderRef.ReserveAmerica(contractCode = "NY", parkId = "489"),
                    campsites =
                        listOf(
                            CatalogCampsiteRef(campsiteId = 253488, vendorId = "253488"),
                        ),
                    startDate = LocalDate.parse("2026-06-22"),
                    endDate = LocalDate.parse("2026-06-24"),
                )

            assertEquals(AvailabilityProviderId.RESERVEAMERICA, adapter.id)
            assertEquals(true, adapter.capabilities.supportsAvailability)
            assertEquals(false, adapter.capabilities.pollableForAlerts)
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
