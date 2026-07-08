package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityWindows
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ResolvedDateWindow
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.service.reservation.CapabilityLimit
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
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
                    resolvedTarget(reservableRid = "site:recgov:100", provider = provider, parentRef = ref),
                    resolvedTarget(reservableRid = "site:recgov:101", provider = provider, parentRef = ref),
                )
            var calls = 0
            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets = targets,
                    windowFor = { _, _ -> windows },
                    fetch = { _, _, reservables, ws ->
                        calls++
                        assertEquals(2, reservables.size)
                        emptyBatch(ws.fetch)
                    },
                )
            assertEquals(1, calls)
            assertEquals(1, results.size)
            assertEquals(FetchOutcome.OK, results[0].outcome)
            assertEquals(2, results[0].reservables.size)
        }

    @Test
    fun `distinct campgrounds produce distinct calls`() =
        runBlocking {
            val provider = fakeProvider()
            val targets =
                listOf(
                    resolvedTarget("site:recgov:1", provider, ProviderRef.RecGov("100")),
                    resolvedTarget("site:recgov:2", provider, ProviderRef.RecGov("200")),
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
            val targets = listOf(resolvedTarget("site:recgov:1", provider, ProviderRef.RecGov("100")))
            val thrown = ReservationProviderError.RateLimited(RuntimeException("429"))
            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets,
                    { _, _ -> windows },
                    { _, _, _, _ -> throw thrown },
                )
            assertEquals(FetchOutcome.RATE_LIMITED, results[0].outcome)
            assertNull(results[0].batch)
            assertNotNull(results[0].providerError)
            assertTrue(results[0].providerError is ReservationProviderError.RateLimited)
            assertEquals(thrown, results[0].providerError)
        }

    @Test
    fun `null window skips the group with no fetch call`() =
        runBlocking {
            val provider = fakeProvider()
            val targets = listOf(resolvedTarget("site:recgov:1", provider, ProviderRef.RecGov("100")))
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
            val targets = listOf(resolvedTarget("site:recgov:1", provider, ProviderRef.RecGov("100")))
            val results =
                CatalogAvailabilityBatcher().fetchByGroup(
                    targets,
                    { _, _ -> AvailabilityWindows(target, fetch) },
                    { _, _, _, ws -> emptyBatch(ws.fetch) },
                )
            assertEquals(fetch, results[0].window)
        }

    // --- fixtures ---

    private fun fakeProvider(): ReservationProvider =
        object : ReservationProvider {
            override val id: ReservationProviderId = ReservationProviderId.RECGOV
            override val capabilities: ReservationProviderCapabilities =
                ReservationProviderCapabilities(
                    supportsAvailability = true,
                    supportsAlerts = true,
                    maxPollWindowDays = 60,
                    bookingHorizon = CapabilityLimit(180, ChronoUnit.DAYS),
                    fetchWindowCap = CapabilityLimit(60, ChronoUnit.DAYS),
                )

            override suspend fun availability(
                ref: ProviderRef,
                startDate: LocalDate,
                endDate: LocalDate,
            ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used by fetchByGroup tests")
        }

    private fun reservable(identityText: String): Reservable =
        Reservable(
            id = 1L,
            identity = ReservableId.parse(identityText)!!,
            name = null,
            loop = null,
            siteType = null,
            raw = null,
        )

    private fun resolvedTarget(
        reservableRid: String,
        provider: ReservationProvider,
        parentRef: ProviderRef,
        parentPoiId: Long = 1L,
    ): ResolvedAvailabilityTarget =
        ResolvedAvailabilityTarget(
            reservable = reservable(reservableRid),
            provider = provider,
            parentRef = parentRef,
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
