package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityWindows
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.availability.ResolvedDateWindow
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.testCampground
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
            val ref = BookingProviderRef.RecGov(facilityId = "232447")
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
                    resolvedTarget(1L, provider, BookingProviderRef.RecGov("100")),
                    resolvedTarget(2L, provider, BookingProviderRef.RecGov("200")),
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
            val targets = listOf(resolvedTarget(1L, provider, BookingProviderRef.RecGov("100")))
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
            val targets = listOf(resolvedTarget(1L, provider, BookingProviderRef.RecGov("100")))
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
            val targets = listOf(resolvedTarget(1L, provider, BookingProviderRef.RecGov("100")))
            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets,
                    { _, _ -> AvailabilityWindows(target, fetch) },
                    { _, _, _, ws -> emptyBatch(ws.fetch) },
                )
            assertEquals(fetch, results[0].window)
        }

    @Test
    fun `passes provider-specific targets to grouped fetch`() =
        runBlocking {
            val provider = fakeProvider()
            val targets =
                listOf(
                    resolvedTarget(
                        campsiteId = 1L,
                        vendor = "campflare",
                        vendorId = "upper-pines-site-100",
                        provider = provider,
                        parentRef = BookingProviderRef.RecGov("232447"),
                    ),
                )

            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets = targets,
                    windowFor = { _, _ -> windows },
                    fetch = { _, _, targets, ws ->
                        assertEquals(listOf(1L), targets.map { it.campsite.id })
                        emptyBatch(ws.fetch)
                    },
                )
            assertEquals(1, results.size)
        }

    // --- fixtures ---

    private fun fakeProvider(): AvailabilityProvider =
        object : AvailabilityProvider {
            override val id: BookingProvider = BookingProvider.RECGOV
            override val capabilities: AvailabilityProviderCapabilities =
                AvailabilityProviderCapabilities(
                    supportsInternalPolling = true,
                    bookingHorizonDays = 180,
                    maxPollWindowDays = 60,
                )

            override fun isEnabled(): Boolean = true

            override suspend fun availability(
                campground: Campground,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used by fetchByGroup tests")
        }

    private fun campsite(
        campsiteId: Long,
        vendor: String,
        vendorId: String,
    ): Campsite =
        campsiteFixture(
            id = campsiteId,
            vendor = vendor,
            vendorId = vendorId,
            name = "",
            loopName = null,
            kind = null,
            sourcePayload = null,
        )

    private fun resolvedTarget(
        campsiteId: Long,
        provider: AvailabilityProvider,
        parentRef: BookingProviderRef,
        parentPoiId: Long = 1L,
        vendor: String = "recgov",
        vendorId: String = campsiteId.toString(),
    ): ResolvedAvailabilityTarget {
        val campground = campgroundForRef(parentRef)
        return ResolvedAvailabilityTarget(
            campsite = campsite(campsiteId, vendor, vendorId),
            provider = provider,
            campground = campground,
            parentPoiId = parentPoiId,
            dateContext = PoiDateContext(timeZone = ZoneOffset.UTC, earliestDate = window.startDate),
        )
    }

    private fun campgroundForRef(ref: BookingProviderRef): Campground {
        val refStr =
            when (ref) {
                is BookingProviderRef.RecGov -> ref.facilityId
                is BookingProviderRef.Campflare -> ref.campgroundId
                is BookingProviderRef.Aspira ->
                    "${ref.tenant}:${ref.transactionLocationId}:${ref.mapId}:${ref.resourceLocationId}"
                is BookingProviderRef.ReserveAmerica -> "${ref.contractCode}:${ref.parkId}"
                is BookingProviderRef.ReserveCalifornia ->
                    "${ref.placeId}:${ref.facilityIds.joinToString(",")}"
            }
        return testCampground(
            bookingProvider = ref.provider.id,
            bookingProviderRef = refStr,
        )
    }

    private fun emptyBatch(w: ResolvedDateWindow): AvailabilityObservationBatch =
        AvailabilityObservationBatch(
            provider = "recgov",
            startDate = w.startDate,
            endDate = w.endDate,
            observations = emptyList(),
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
        )
}
