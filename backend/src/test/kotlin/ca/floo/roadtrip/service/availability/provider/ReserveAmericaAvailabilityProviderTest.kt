package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.client.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
                    tenants = shippedTenantRegistry().tenantsOf(BookingProvider.RESERVEAMERICA),
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
            assertEquals(BookingProviderRef.ReserveAmerica(contractCode = "NY", parkId = "489"), batch.scope)
            assertEquals(2, batch.observations.size)
            assertEquals(
                listOf(AvailabilityStatus.RESERVED, AvailabilityStatus.AVAILABLE),
                batch.observations.sortedBy { it.date }.map { it.status },
            )
            assertEquals(setOf(253488L), batch.observations.map { it.campsiteId }.toSet())
        }

    @Test
    fun `reserveamerica claims a campground through its alias when the primary belongs to another provider`() {
        val adapter =
            ReserveAmericaAvailabilityProvider(
                tenants = shippedTenantRegistry().tenantsOf(BookingProvider.RESERVEAMERICA),
                availabilityClient = ReserveAmericaAvailabilityClient { _, _, _, _, _ -> error("not stubbed") },
                enabled = true,
            )
        val aliased =
            testCampground(
                bookingProvider = "campflare",
                bookingProviderRef = "some-campflare-id",
                bookingAliases = listOf(BookingAlias(provider = BookingProvider.RESERVEAMERICA, ref = "NY:489")),
            )

        assertTrue(adapter.supportsCampground(aliased))
    }

    @Test
    fun `an alias for an unconfigured contract is not supported`() {
        val adapter =
            ReserveAmericaAvailabilityProvider(
                tenants = emptyList(),
                availabilityClient = ReserveAmericaAvailabilityClient { _, _, _, _, _ -> error("not stubbed") },
                enabled = true,
            )
        val aliased =
            testCampground(
                bookingProvider = "campflare",
                bookingProviderRef = "some-campflare-id",
                bookingAliases = listOf(BookingAlias(provider = BookingProvider.RESERVEAMERICA, ref = "NY:489")),
            )

        assertFalse(adapter.supportsCampground(aliased))
    }
}
