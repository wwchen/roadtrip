package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.models.domain.ProviderRef
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class AvailabilityProviderRegistryTest {
    private class FakeProvider(
        override val id: AvailabilityProviderId,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities = AvailabilityProviderCapabilities.UNSUPPORTED

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = error("not used in this test")
    }

    @Test
    fun `forPoi resolves source to its adapter instance`() {
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV)
        val aspiraPc = FakeProvider(AvailabilityProviderId.ASPIRA)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "federal-campgrounds" to recgov,
                        "aspira-pc-pins" to aspiraPc,
                    ),
            )

        val resolved = registry.forPoi(row("federal-campgrounds"))
        assertNotNull(resolved)
        assertEquals(AvailabilityProviderId.RECGOV, resolved.id)

        val pc = registry.forPoi(row("aspira-pc-pins"))
        assertNotNull(pc)
        assertEquals(AvailabilityProviderId.ASPIRA, pc.id)
    }

    @Test
    fun `forPoi returns null for unmapped source`() {
        val registry = AvailabilityProviderRegistry(adaptersBySource = emptyMap())
        assertNull(registry.forPoi(row("never-registered")))
    }

    @Test
    fun `forPoi with ref returns null when mapped provider declines the ref`() {
        val declining =
            object : AvailabilityProvider {
                override val id: AvailabilityProviderId = AvailabilityProviderId.CAMPFLARE
                override val capabilities: AvailabilityProviderCapabilities = AvailabilityProviderCapabilities.UNSUPPORTED

                override fun canHandle(ref: ProviderRef): Boolean = false

                override suspend fun availability(
                    ref: ProviderRef,
                    startDate: LocalDate,
                    endDate: LocalDate,
                ): AvailabilityObservationBatch = error("not used in this test")
            }
        val registry = AvailabilityProviderRegistry(adaptersBySource = mapOf("campflare" to declining))

        assertSame(declining, registry.forPoi(row("campflare")))
        assertNull(registry.forPoi(row("campflare"), ProviderRef.Campflare("upper-pines-campground-447")))
    }

    @Test
    fun `forSource resolves source without requiring a campground row`() {
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource = mapOf("federal-campgrounds" to recgov),
            )

        assertSame(recgov, registry.forSource("federal-campgrounds"))
        assertNull(registry.forSource("never-registered"))
    }

    @Test
    fun `multiple sources can share one adapter instance`() {
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "federal-campgrounds" to recgov,
                        "another-recgov-source" to recgov,
                    ),
            )
        assertSame(recgov, registry.forPoi(row("federal-campgrounds")))
        assertSame(recgov, registry.forPoi(row("another-recgov-source")))
        assertEquals(1, registry.all().size)
    }

    @Test
    fun `multiple Aspira tenants share an id but have distinct instances`() {
        val pc = FakeProvider(AvailabilityProviderId.ASPIRA)
        val bc = FakeProvider(AvailabilityProviderId.ASPIRA)
        val wa = FakeProvider(AvailabilityProviderId.ASPIRA)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "aspira-pc-pins" to pc,
                        "aspira-bc-pins" to bc,
                        "aspira-wa-pins" to wa,
                    ),
            )
        assertSame(pc, registry.forPoi(row("aspira-pc-pins")))
        assertSame(bc, registry.forPoi(row("aspira-bc-pins")))
        assertSame(wa, registry.forPoi(row("aspira-wa-pins")))
        assertEquals(3, registry.all().size)
    }

    private fun row(source: String): CampsiteProviderRefRow = CampsiteProviderRefRow(poiId = 1L, source = source, providerRefJson = "{}")
}
