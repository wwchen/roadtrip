package ca.floo.roadtrip.service.reservation

import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.reservation.adapters.reserveamerica.ReserveAmericaReservationProvider
import ca.floo.roadtrip.service.reservation.adapters.reserveamerica.ReserveAmericaTenant
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals

class ReserveAmericaReservationProviderTest {
    @Test
    fun `availability fetch cost counts fourteen day pages`() {
        val adapter =
            ReserveAmericaReservationProvider(
                tenant =
                    ReserveAmericaTenant(
                        source = "new-york-state-parks",
                        host = "newyorkstateparks.reserveamerica.com",
                        contractCode = "NY",
                        bookingHorizon = CapabilityLimit(270, ChronoUnit.DAYS),
                    ),
                client = ReserveAmericaAvailabilityClient { _, _, _, _, _ -> error("not used") },
            )

        assertEquals(
            2L,
            adapter.availabilityFetchCost(
                startDate = LocalDate.parse("2026-07-02"),
                endDate = LocalDate.parse("2026-07-30"),
            ),
        )
    }

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
                ReserveAmericaReservationProvider(
                    tenant =
                        ReserveAmericaTenant(
                            source = "new-york-state-parks",
                            host = "newyorkstateparks.reserveamerica.com",
                            contractCode = "NY",
                            bookingHorizon = CapabilityLimit(270, ChronoUnit.DAYS),
                        ),
                    client = client,
                )

            val batch =
                adapter.catalogAvailability(
                    ref = ProviderRef.ReserveAmerica(contractCode = "NY", parkId = "489"),
                    reservables =
                        listOf(
                            CatalogReservableRef(rid = "site:reserveamerica_ny:253488", vendorId = "253488"),
                        ),
                    startDate = LocalDate.parse("2026-06-22"),
                    endDate = LocalDate.parse("2026-06-24"),
                )

            assertEquals(ReservationProviderId.RESERVEAMERICA, adapter.id)
            assertEquals(true, adapter.capabilities.supportsAvailability)
            assertEquals(false, adapter.capabilities.supportsAlerts)
            assertEquals("reserveamerica", batch.provider)
            assertEquals("489", batch.campgroundId)
            assertEquals(2, batch.observations.size)
            assertEquals(
                listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.AVAILABLE),
                batch.observations.sortedBy { it.date }.map { it.status },
            )
            assertEquals(setOf("site:reserveamerica_ny:253488"), batch.observations.map { it.reservableId }.toSet())
        }
}
