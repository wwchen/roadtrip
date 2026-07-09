package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityWindows
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.models.domain.Campsite
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderCapabilities
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderError
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.CatalogCampsiteRef
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CatalogAvailabilityBatcherTest {
    private val window = ResolvedDateWindow(LocalDate.parse("2026-07-17"), LocalDate.parse("2026-07-31"))
    private val windows = AvailabilityWindows(target = window, fetch = window)

    @Test
    fun `groups same-campground targets into one fetch call`() =
        runBlocking {
            // Two targets share provider + parentRef → exactly one fetch call.
            val provider = fakeProvider()
            val ref = ProviderRef.RecGov(recgovId = "232447")
            val targets =
                listOf(
                    resolvedTarget(campsiteId = 100L, provider = provider, parentRef = ref),
                    resolvedTarget(campsiteId = 101L, provider = provider, parentRef = ref),
                )
            var calls = 0
            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets = targets,
                    windowFor = { _, _ -> windows },
                    fetch = { _, _, targets, ws ->
                        calls++
                        assertEquals(2, targets.size)
                        emptyBatch(ws.fetch)
                    },
                )
            assertEquals(1, calls)
            assertEquals(1, results.size)
            assertEquals(FetchOutcome.OK, results[0].outcome)
            assertEquals(2, results[0].campsites.size)
        }

    @Test
    fun `distinct campgrounds produce distinct calls`() =
        runBlocking {
            val provider = fakeProvider()
            val targets =
                listOf(
                    resolvedTarget(1L, provider, ProviderRef.RecGov("100")),
                    resolvedTarget(2L, provider, ProviderRef.RecGov("200")),
                )
            var calls = 0
            CatalogAvailabilityBatcher().fetchByGroup(targets, { _, _ -> windows }, { _, _, _, ws ->
                calls++
                emptyBatch(ws.fetch)
            })
            assertEquals(2, calls)
        }

    @Test
    fun `rate limited fetch is classified, not thrown`() =
        runBlocking {
            val provider = fakeProvider()
            val targets = listOf(resolvedTarget(1L, provider, ProviderRef.RecGov("100")))
            val thrown = AvailabilityProviderError.RateLimited(RuntimeException("429"))
            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets,
                    { _, _ -> windows },
                    { _, _, _, _ -> throw thrown },
                )
            assertEquals(FetchOutcome.RATE_LIMITED, results[0].outcome)
            assertNull(results[0].batch)
            assertNotNull(results[0].providerError)
            assertTrue(results[0].providerError is AvailabilityProviderError.RateLimited)
            assertEquals(thrown, results[0].providerError)
        }

    @Test
    fun `null window skips the group with no fetch call`() =
        runBlocking {
            val provider = fakeProvider()
            val targets = listOf(resolvedTarget(1L, provider, ProviderRef.RecGov("100")))
            var calls = 0
            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets,
                    { _, _ -> null },
                    { _, _, _, ws ->
                        calls++
                        emptyBatch(ws.fetch)
                    },
                )
            assertEquals(0, calls)
            assertNull(results[0].window)
            assertEquals(FetchOutcome.OK, results[0].outcome)
        }

    @Test
    fun `records the fetch window from the windows pair`() =
        runBlocking {
            val provider = fakeProvider()
            val target = ResolvedDateWindow(LocalDate.parse("2026-07-17"), LocalDate.parse("2026-07-24"))
            val fetch = ResolvedDateWindow(LocalDate.parse("2026-07-17"), LocalDate.parse("2026-08-16"))
            val targets = listOf(resolvedTarget(1L, provider, ProviderRef.RecGov("100")))
            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets,
                    { _, _ -> AvailabilityWindows(target, fetch) },
                    { _, _, _, ws -> emptyBatch(ws.fetch) },
                )
            assertEquals(fetch, results[0].window)
        }

    @Test
    fun `passes provider-specific catalog refs to grouped fetch`() =
        runBlocking {
            val provider = fakeProvider()
            val catalogRef =
                CatalogCampsiteRef(
                    campsiteId = 1L,
                    vendorId = "100",
                )
            val targets =
                listOf(
                    resolvedTarget(
                        campsiteId = 1L,
                        vendor = "campflare",
                        vendorId = "upper-pines-site-100",
                        provider = provider,
                        parentRef = ProviderRef.RecGov("232447"),
                        catalogRef = catalogRef,
                    ),
                )

            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets = targets,
                    windowFor = { _, _ -> windows },
                    fetch = { _, _, targets, ws ->
                        assertEquals(listOf(catalogRef), targets.map { it.catalogRef })
                        emptyBatch(ws.fetch)
                    },
                )
            assertEquals(1, results.size)
        }

    // --- fixtures ---

    private fun fakeProvider(): AvailabilityProvider =
        object : AvailabilityProvider {
            override val id: AvailabilityProviderId = AvailabilityProviderId.RECGOV
            override val capabilities: AvailabilityProviderCapabilities =
                AvailabilityProviderCapabilities(
                    supportsAvailability = true,
                    supportsAlerts = true,
                    bookingHorizonDays = 180,
                    maxPollWindowDays = 60,
                )

            override suspend fun availability(
                ref: ProviderRef,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used by fetchByGroup tests")
        }

    private fun campsite(
        campsiteId: Long,
        vendor: String,
        vendorId: String,
    ): Campsite =
        Campsite(
            id = campsiteId,
            vendor = vendor,
            vendorId = vendorId,
            name = null,
            loop = null,
            siteType = null,
            raw = null,
        )

    private fun resolvedTarget(
        campsiteId: Long,
        provider: AvailabilityProvider,
        parentRef: ProviderRef,
        parentPoiId: Long = 1L,
        vendor: String = "recgov",
        vendorId: String = campsiteId.toString(),
        catalogRef: CatalogCampsiteRef =
            CatalogCampsiteRef(
                campsiteId = campsiteId,
                vendorId = vendorId,
            ),
    ): ResolvedAvailabilityTarget =
        ResolvedAvailabilityTarget(
            campsite = campsite(campsiteId, vendor, vendorId),
            provider = provider,
            parentRef = parentRef,
            catalogRef = catalogRef,
            parentPoiId = parentPoiId,
            dateContext = PoiDateContext(timeZone = ZoneOffset.UTC, earliestDate = window.startDate),
        )

    private fun emptyBatch(w: ResolvedDateWindow): AvailabilityObservationBatch =
        AvailabilityObservationBatch(
            provider = "recgov",
            startDate = w.startDate,
            endDate = w.endDate,
            observations = emptyList(),
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
        )
}
