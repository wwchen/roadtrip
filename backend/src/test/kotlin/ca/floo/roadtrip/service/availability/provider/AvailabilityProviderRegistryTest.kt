package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.model.domain.ProviderRef
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class AvailabilityProviderRegistryTest {
    private class FakeProvider(
        override val id: AvailabilityProviderId,
        private val enabled: Boolean,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities = AvailabilityProviderCapabilities.unsupported

        override fun isEnabled(): Boolean = enabled

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = error("not used in this test")
    }

    @Test
    fun `forPoi resolves source to its adapter instance`() {
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV, enabled = true)
        val aspiraPc = FakeProvider(AvailabilityProviderId.ASPIRA, enabled = true)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "federal-campgrounds" to recgov,
                        "aspira_pc" to aspiraPc,
                    ),
            )

        val resolved = registry.forPoi(row("federal-campgrounds"))
        assertNotNull(resolved)
        assertEquals(AvailabilityProviderId.RECGOV, resolved.id)

        val pc = registry.forPoi(row("aspira_pc"))
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
                override val capabilities: AvailabilityProviderCapabilities = AvailabilityProviderCapabilities.unsupported

                override fun isEnabled(): Boolean = true

                override fun supportsRef(ref: ProviderRef): Boolean = false

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
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV, enabled = true)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource = mapOf("federal-campgrounds" to recgov),
            )

        assertSame(recgov, registry.forSource("federal-campgrounds"))
        assertNull(registry.forSource("never-registered"))
    }

    @Test
    fun `multiple sources can share one adapter instance`() {
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV, enabled = true)
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
    fun `disabled providers are hidden from lookup helpers`() {
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV, enabled = true)
        val campflare = FakeProvider(AvailabilityProviderId.CAMPFLARE, enabled = false)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "federal-campgrounds" to recgov,
                        "campflare-campgrounds" to campflare,
                        "campflare" to campflare,
                    ),
            )

        assertSame(recgov, registry.forPoi(row("federal-campgrounds")))
        assertNull(registry.forPoi(row("campflare-campgrounds")))
        assertNull(registry.forPoi(row("campflare"), ProviderRef.Campflare("upper-pines-campground-447")))
        assertSame(recgov, registry.firstByVendor(AvailabilityProviderId.RECGOV))
        assertNull(registry.firstByVendor(AvailabilityProviderId.CAMPFLARE))
        assertEquals(listOf(AvailabilityProviderId.RECGOV), registry.all().map { it.id })
    }

    @Test
    fun `multiple Aspira tenants share an id but have distinct instances`() {
        val pc = FakeProvider(AvailabilityProviderId.ASPIRA, enabled = true)
        val bc = FakeProvider(AvailabilityProviderId.ASPIRA, enabled = true)
        val wa = FakeProvider(AvailabilityProviderId.ASPIRA, enabled = true)
        val registry =
            AvailabilityProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "aspira_pc" to pc,
                        "aspira_bc" to bc,
                        "aspira_wa" to wa,
                    ),
            )
        assertSame(pc, registry.forPoi(row("aspira_pc")))
        assertSame(bc, registry.forPoi(row("aspira_bc")))
        assertSame(wa, registry.forPoi(row("aspira_wa")))
        assertEquals(3, registry.all().size)
    }

    private fun row(source: String): CampsiteProviderRefRow = CampsiteProviderRefRow(poiId = 1L, source = source, providerRefJson = "{}")
}
