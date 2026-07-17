package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.models.domain.CampsiteProviderRefRow
import ca.floo.roadtrip.models.domain.ProviderRef
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class AvailabilityProviderResolutionTest {
    private class FakeProvider(
        override val id: AvailabilityProviderId,
        private val enabled: Boolean,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities = AvailabilityProviderCapabilities.UNSUPPORTED

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
        val providersBySource =
            mapOf(
                "federal-campgrounds" to recgov,
                "aspira-pc-pins" to aspiraPc,
            )

        val resolved = providersBySource.availabilityProviderFor(row("federal-campgrounds"))
        assertNotNull(resolved)
        assertEquals(AvailabilityProviderId.RECGOV, resolved.id)

        val pc = providersBySource.availabilityProviderFor(row("aspira-pc-pins"))
        assertNotNull(pc)
        assertEquals(AvailabilityProviderId.ASPIRA, pc.id)
    }

    @Test
    fun `forPoi returns null for unmapped source`() {
        assertNull(emptyMap<String, AvailabilityProvider>().availabilityProviderFor(row("never-registered")))
    }

    @Test
    fun `forPoi with ref returns null when mapped provider declines the ref`() {
        val declining =
            object : AvailabilityProvider {
                override val id: AvailabilityProviderId = AvailabilityProviderId.CAMPFLARE
                override val capabilities: AvailabilityProviderCapabilities = AvailabilityProviderCapabilities.UNSUPPORTED

                override fun isEnabled(): Boolean = true

                override fun supportsRef(ref: ProviderRef): Boolean = false

                override suspend fun availability(
                    ref: ProviderRef,
                    startDate: LocalDate,
                    endDate: LocalDate,
                ): AvailabilityObservationBatch = error("not used in this test")
            }
        val providersBySource = mapOf("campflare" to declining)

        assertSame(declining, providersBySource.availabilityProviderFor(row("campflare")))
        assertNull(providersBySource.availabilityProviderFor(row("campflare"), ProviderRef.Campflare("upper-pines-campground-447")))
    }

    @Test
    fun `forSource resolves source without requiring a campground row`() {
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV, enabled = true)
        val providersBySource = mapOf("federal-campgrounds" to recgov)

        assertSame(recgov, providersBySource.availabilityProviderForSource("federal-campgrounds"))
        assertNull(providersBySource.availabilityProviderForSource("never-registered"))
    }

    @Test
    fun `multiple sources can share one adapter instance`() {
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV, enabled = true)
        val providersBySource =
            mapOf(
                "federal-campgrounds" to recgov,
                "another-recgov-source" to recgov,
            )
        assertSame(recgov, providersBySource.availabilityProviderFor(row("federal-campgrounds")))
        assertSame(recgov, providersBySource.availabilityProviderFor(row("another-recgov-source")))
        assertEquals(1, providersBySource.values.toSet().size)
    }

    @Test
    fun `disabled providers are hidden from lookup helpers`() {
        val recgov = FakeProvider(AvailabilityProviderId.RECGOV, enabled = true)
        val campflare = FakeProvider(AvailabilityProviderId.CAMPFLARE, enabled = false)
        val providersBySource =
            mapOf(
                "federal-campgrounds" to recgov,
                "campflare-campgrounds" to campflare,
                "campflare" to campflare,
            )

        assertSame(recgov, providersBySource.availabilityProviderFor(row("federal-campgrounds")))
        assertNull(providersBySource.availabilityProviderFor(row("campflare-campgrounds")))
        assertNull(providersBySource.availabilityProviderFor(row("campflare"), ProviderRef.Campflare("upper-pines-campground-447")))
    }

    @Test
    fun `multiple Aspira tenants share an id but have distinct instances`() {
        val pc = FakeProvider(AvailabilityProviderId.ASPIRA, enabled = true)
        val bc = FakeProvider(AvailabilityProviderId.ASPIRA, enabled = true)
        val wa = FakeProvider(AvailabilityProviderId.ASPIRA, enabled = true)
        val providersBySource =
            mapOf(
                "aspira-pc-pins" to pc,
                "aspira-bc-pins" to bc,
                "aspira-wa-pins" to wa,
            )
        assertSame(pc, providersBySource.availabilityProviderFor(row("aspira-pc-pins")))
        assertSame(bc, providersBySource.availabilityProviderFor(row("aspira-bc-pins")))
        assertSame(wa, providersBySource.availabilityProviderFor(row("aspira-wa-pins")))
        assertEquals(3, providersBySource.values.toSet().size)
    }

    private fun row(source: String): CampsiteProviderRefRow = CampsiteProviderRefRow(poiId = 1L, source = source, providerRefJson = "{}")
}
