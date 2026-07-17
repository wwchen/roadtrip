package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.clients.campflare.CampflareAvailabilityClient
import ca.floo.roadtrip.clients.recgov.Campsite
import ca.floo.roadtrip.clients.recgov.RecGovAvailabilityClient
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailability
import ca.floo.roadtrip.clients.reserveamerica.ReserveAmericaAvailabilityClient
import ca.floo.roadtrip.models.availability.campflare.CampflareAvailability
import ca.floo.roadtrip.models.availability.campflare.CampflareCampgroundAvailability
import ca.floo.roadtrip.models.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.adapters.campflare.CampflareAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaAvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.adapters.reserveamerica.ReserveAmericaTenant
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AvailabilityProviderRegistryBuildTest {
    @Test
    fun `reserveamerica provider passes registry tenant args to shared client`() =
        runBlocking {
            var observedCall: ReserveAmericaCall? = null
            val provider =
                ReserveAmericaAvailabilityProvider(
                    tenant =
                        ReserveAmericaTenant(
                            source = "test-reserveamerica",
                            host = "example.reserveamerica.test",
                            contractCode = "ZZ",
                            bookingHorizonDays = 123,
                        ),
                    client =
                        ReserveAmericaAvailabilityClient { host, contractCode, parkId, startDate, endDate ->
                            observedCall = ReserveAmericaCall(host, contractCode, parkId, startDate, endDate)
                            ReserveAmericaAvailability(
                                contractCode = contractCode,
                                parkId = parkId,
                                startDate = startDate,
                                endDate = endDate,
                                observedAt = Instant.EPOCH,
                                statuses = emptyMap(),
                            )
                        },
                    enabled = true,
                )
            val registry =
                AvailabilityProviderRegistry.fromBindings(
                    listOf(AvailabilityProviderBinding(source = "test-reserveamerica", provider = provider)),
                )

            val resolved = registry.forPoi(row("test-reserveamerica"))

            assertNotNull(resolved)
            assertEquals(AvailabilityProviderId.RESERVEAMERICA, resolved.id)
            resolved.availability(
                ref = ProviderRef.ReserveAmerica(contractCode = "ZZ", parkId = "489"),
                startDate = LocalDate.parse("2026-06-22"),
                endDate = LocalDate.parse("2026-06-24"),
            )

            assertEquals(
                ReserveAmericaCall(
                    host = "example.reserveamerica.test",
                    contractCode = "ZZ",
                    parkId = "489",
                    startDate = LocalDate.parse("2026-06-22"),
                    endDate = LocalDate.parse("2026-06-24"),
                ),
                observedCall,
            )
        }

    @Test
    fun `campflare source and canonical vendor key map to campflare provider`() =
        runBlocking {
            var observedCall: CampflareCall? = null
            val provider =
                CampflareAvailabilityProvider(
                    client =
                        CampflareAvailabilityClient { campgroundIds, startDate, endDate ->
                            observedCall = CampflareCall(campgroundIds, startDate, endDate)
                            CampflareAvailability(
                                campgrounds =
                                    campgroundIds.associateWith {
                                        CampflareCampgroundAvailability(
                                            campgroundId = it,
                                            campsiteAvailability = emptyList(),
                                        )
                                    },
                                observedAt = Instant.EPOCH,
                            )
                        },
                    enabled = true,
                )
            val registry =
                AvailabilityProviderRegistry.fromBindings(
                    listOf(
                        AvailabilityProviderBinding(source = "campflare-campgrounds", provider = provider),
                        AvailabilityProviderBinding(source = "campflare", provider = provider),
                    ),
                )

            val resolved = registry.forPoi(row("campflare-campgrounds"))
            val resolvedByCanonicalVendor = registry.forPoi(row("campflare"))

            assertNotNull(resolved)
            assertNotNull(resolvedByCanonicalVendor)
            assertEquals(AvailabilityProviderId.CAMPFLARE, resolved.id)
            assertEquals(AvailabilityProviderId.CAMPFLARE, resolvedByCanonicalVendor.id)
            resolved.availability(
                ref = ProviderRef.Campflare("upper-pines-campground-447"),
                startDate = LocalDate.parse("2026-06-01"),
                endDate = LocalDate.parse("2026-06-07"),
            )
            assertEquals(
                CampflareCall(
                    campgroundIds = listOf("upper-pines-campground-447"),
                    startDate = LocalDate.parse("2026-06-01"),
                    endDate = LocalDate.parse("2026-06-07"),
                ),
                observedCall,
            )
        }

    @Test
    fun `unconfigured campflare provider declines campflare refs so recgov aliases can be fallback`() {
        val campflare = CampflareAvailabilityProvider(client = stubCampflareClient(), enabled = false)
        val recgov = RecGovAvailabilityProvider(client = stubRecgovClient(), enabled = true)
        val registry =
            AvailabilityProviderRegistry.fromBindings(
                listOf(
                    AvailabilityProviderBinding(source = "campflare", provider = campflare),
                    AvailabilityProviderBinding(source = "campflare-campgrounds", provider = campflare),
                    AvailabilityProviderBinding(source = "recgov", provider = recgov),
                    AvailabilityProviderBinding(source = "federal-campgrounds", provider = recgov),
                ),
            )

        assertNull(registry.forPoi(row("campflare")))
        assertNull(registry.forPoi(row("campflare-campgrounds")))
        assertNull(registry.forPoi(row("campflare"), ProviderRef.Campflare("upper-pines-campground-447")))
        assertNull(registry.forPoi(row("campflare-campgrounds"), ProviderRef.Campflare("upper-pines-campground-447")))
        assertEquals(
            AvailabilityProviderId.RECGOV,
            registry.forPoi(row("recgov"), ProviderRef.RecGov("232447"))?.id,
        )
        assertEquals(
            AvailabilityProviderId.RECGOV,
            registry.forPoi(row("federal-campgrounds"), ProviderRef.RecGov("232447"))?.id,
        )
    }

    @Test
    fun `duplicate source bindings fail fast`() {
        val recgov = RecGovAvailabilityProvider(client = stubRecgovClient(), enabled = true)

        assertFailsWith<IllegalArgumentException> {
            AvailabilityProviderRegistry.fromBindings(
                listOf(
                    AvailabilityProviderBinding(source = "recgov", provider = recgov),
                    AvailabilityProviderBinding(source = "recgov", provider = recgov),
                ),
            )
        }
    }

    private data class ReserveAmericaCall(
        val host: String,
        val contractCode: String,
        val parkId: String,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private data class CampflareCall(
        val campgroundIds: List<String>,
        val startDate: LocalDate,
        val endDate: LocalDate,
    )

    private fun row(source: String): CampsiteProviderRefRow = CampsiteProviderRefRow(poiId = 1L, source = source, providerRefJson = "{}")

    private fun stubRecgovClient(): RecGovAvailabilityClient =
        object : RecGovAvailabilityClient {
            override suspend fun fetchMonth(
                campgroundId: String,
                monthStart: String,
            ): Map<String, Campsite> = emptyMap()
        }

    private fun stubCampflareClient(): CampflareAvailabilityClient = CampflareAvailabilityClient { _, _, _ -> error("not used") }
}
