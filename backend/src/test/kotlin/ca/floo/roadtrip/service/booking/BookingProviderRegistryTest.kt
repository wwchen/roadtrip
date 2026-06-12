package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BookingProviderRegistryTest {
    private class FakeProvider(
        override val id: BookingProviderId,
    ) : BookingProvider {
        override val capabilities: BookingCapabilities = BookingCapabilities.UNSUPPORTED

        override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto = error("not used in this test")

        override suspend fun availableDates(req: AvailableDatesRequest): List<String> = emptyList()
    }

    @Test
    fun `forPoi resolves source through to adapter`() {
        val recgov = FakeProvider(BookingProviderId.RECGOV)
        val aspiraPc = FakeProvider(BookingProviderId.ASPIRA_PC)
        val registry =
            BookingProviderRegistry(
                adapters =
                    mapOf(
                        BookingProviderId.RECGOV to recgov,
                        BookingProviderId.ASPIRA_PC to aspiraPc,
                    ),
                sourceToProviderId =
                    mapOf(
                        "federal-campgrounds" to BookingProviderId.RECGOV,
                        "aspira-pc-pins" to BookingProviderId.ASPIRA_PC,
                    ),
            )

        val resolved = registry.forPoi(row("federal-campgrounds"))
        assertNotNull(resolved)
        assertEquals(BookingProviderId.RECGOV, resolved.id)

        val pc = registry.forPoi(row("aspira-pc-pins"))
        assertNotNull(pc)
        assertEquals(BookingProviderId.ASPIRA_PC, pc.id)
    }

    @Test
    fun `forPoi returns null for unmapped source`() {
        val registry =
            BookingProviderRegistry(
                adapters = emptyMap(),
                sourceToProviderId = emptyMap(),
            )
        assertNull(registry.forPoi(row("never-registered")))
    }

    @Test
    fun `forPoi returns null when source maps to id but adapter missing`() {
        val registry =
            BookingProviderRegistry(
                adapters = emptyMap(),
                sourceToProviderId = mapOf("federal-campgrounds" to BookingProviderId.RECGOV),
            )
        assertNull(registry.forPoi(row("federal-campgrounds")))
    }

    private fun row(source: String): CampsiteProviderRefRow = CampsiteProviderRefRow(poiId = 1L, source = source, providerRefJson = "{}")
}
