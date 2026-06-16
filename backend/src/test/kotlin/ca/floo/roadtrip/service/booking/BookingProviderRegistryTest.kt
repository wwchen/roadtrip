package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class BookingProviderRegistryTest {
    private class FakeProvider(
        override val id: BookingProviderId,
    ) : BookingProvider {
        override val capabilities: BookingCapabilities = BookingCapabilities.UNSUPPORTED

        override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto = error("not used in this test")

        override suspend fun availableDates(req: AvailableDatesRequest): List<String> = emptyList()
    }

    @Test
    fun `forPoi resolves source to its adapter instance`() {
        val recgov = FakeProvider(BookingProviderId.RECGOV)
        val aspiraPc = FakeProvider(BookingProviderId.ASPIRA)
        val registry =
            BookingProviderRegistry(
                adaptersBySource =
                    mapOf(
                        "federal-campgrounds" to recgov,
                        "aspira-pc-pins" to aspiraPc,
                    ),
            )

        val resolved = registry.forPoi(row("federal-campgrounds"))
        assertNotNull(resolved)
        assertEquals(BookingProviderId.RECGOV, resolved.id)

        val pc = registry.forPoi(row("aspira-pc-pins"))
        assertNotNull(pc)
        assertEquals(BookingProviderId.ASPIRA, pc.id)
    }

    @Test
    fun `forPoi returns null for unmapped source`() {
        val registry = BookingProviderRegistry(adaptersBySource = emptyMap())
        assertNull(registry.forPoi(row("never-registered")))
    }

    @Test
    fun `multiple sources can share one adapter instance`() {
        val recgov = FakeProvider(BookingProviderId.RECGOV)
        val registry =
            BookingProviderRegistry(
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
        val pc = FakeProvider(BookingProviderId.ASPIRA)
        val bc = FakeProvider(BookingProviderId.ASPIRA)
        val wa = FakeProvider(BookingProviderId.ASPIRA)
        val registry =
            BookingProviderRegistry(
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
