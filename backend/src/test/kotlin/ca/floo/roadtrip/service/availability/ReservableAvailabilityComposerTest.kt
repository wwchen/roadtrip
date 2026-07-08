package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.reservation.CapabilityLimit
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private typealias CatalogAvailabilityHandler = (
    ProviderRef,
    List<CatalogReservableRef>,
    LocalDate,
    LocalDate,
) -> AvailabilityObservationBatch

/**
 * Tests for [ReservableAvailabilityComposer.availabilityFor] and its delegation
 * to [CatalogAvailabilityBatcher] + the read-through cache. The cache is a real
 * [AvailabilityRepo] over Testcontainers Postgres — the in-memory `AvailabilityCacheStore`
 * fake was removed with the port itself (single-table realign). Tests that don't
 * exercise caching wire `availability = null` so the composer always fetches.
 */
class ReservableAvailabilityComposerTest : SharedDbTest() {
    // A fixed future earliest date keeps the default window deterministic and
    // clear of AvailabilityDateResolver's earliest-date guard.
    private val earliest = LocalDate.parse("2026-08-01")
    private val longTtl = Duration.ofHours(1)

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
    }

    @Test
    fun `repeat call hits the cache instead of re-fetching upstream`() =
        runBlocking {
            val provider = fakeProvider()
            val ref = ProviderRef.RecGov(recgovId = "232447")
            val target = resolvedTarget("site:recgov:100", provider = provider, parentRef = ref)
            val composer = composer(listOf(target), AvailabilityRepo(ctx))

            val first = composer.availabilityFor(listOf(target.reservable), null, null)
            assertEquals(1, provider.catalogCalls, "cache miss should fetch upstream exactly once")
            assertFalse(first.single().cache.hit, "first read is a miss")

            val second = composer.availabilityFor(listOf(target.reservable), null, null)
            assertEquals(1, provider.catalogCalls, "repeat read must be served from cache, not re-fetched")
            assertTrue(second.single().cache.hit, "second read is a cache hit")
        }

    @Test
    fun `multi-rid request spanning distinct groups splits results per rid`() =
        runBlocking {
            // Two rids under different parent refs → two fetch groups. Each rid's
            // response must carry only its own observations, keyed to its rid.
            val provider =
                fakeProvider { _, reservables, startDate, endDate ->
                    // Group A (site 100) is AVAILABLE; group B (site 200) is RESERVED.
                    val status =
                        if (reservables.single().rid ==
                            "site:recgov:100"
                        ) {
                            AvailabilityStatus.AVAILABLE
                        } else {
                            AvailabilityStatus.RESERVED
                        }
                    batchFor(reservables, startDate, endDate, status)
                }
            val targetA = resolvedTarget("site:recgov:100", provider = provider, parentRef = ProviderRef.RecGov("100"))
            val targetB = resolvedTarget("site:recgov:200", provider = provider, parentRef = ProviderRef.RecGov("200"))
            val composer = composer(listOf(targetA, targetB), AvailabilityRepo(ctx))

            // Request order is B then A; results must come back in the requested order.
            val results = composer.availabilityFor(listOf(targetB.reservable, targetA.reservable), null, null)

            assertEquals(2, provider.catalogCalls, "distinct groups fetch independently")
            assertEquals(2, results.size)
            assertEquals("site:recgov:200", results[0].reservableId)
            assertEquals("site:recgov:100", results[1].reservableId)
            // A's window is fully AVAILABLE; B's is fully RESERVED.
            assertTrue(results[1].availability.all { it.status == AvailabilityStatus.AVAILABLE })
            assertTrue(results[0].availability.all { it.status == AvailabilityStatus.RESERVED })
        }

    @Test
    fun `bad date window from the date resolver propagates out of availabilityFor`() =
        runBlocking {
            val provider = fakeProvider()
            val target = resolvedTarget("site:recgov:100", provider = provider, parentRef = ProviderRef.RecGov("100"))
            val composer = composer(listOf(target), availability = null)

            // startDate before the target's earliest bookable date → StartBeforeEarliest.
            assertFailsWith<AvailabilityServiceError.BadDateWindow.StartBeforeEarliest> {
                composer.availabilityFor(listOf(target.reservable), earliest.minusDays(1), null)
            }
            assertEquals(0, provider.catalogCalls, "an invalid window must short-circuit before any upstream fetch")
        }

    @Test
    fun `a reservable with no resolvable provider propagates UnknownCampground before batching`() =
        runBlocking {
            // The composer resolves each already-loaded reservable via the target
            // resolver; a reservable with no linked provider target resolves to null
            // and must fail the collection before any upstream fetch.
            val composer = composer(targets = emptyList(), availability = null)
            val error =
                assertFailsWith<AvailabilityServiceError.UnknownCampground> {
                    composer.availabilityFor(listOf(reservable("site:recgov:999")), null, null)
                }
            assertEquals(AvailabilityServiceError.UnknownCampground, error)
        }

    @Test
    fun `fetches the snapped provider bucket while requesting a narrower range`() =
        runBlocking {
            // The vendor's day fetch cap defines stable epoch-day buckets.
            // The upstream call should fetch the containing bucket, while the
            // response returned to the caller stays sliced to the requested days.
            var fetchedStart: LocalDate? = null
            var fetchedEnd: LocalDate? = null
            val provider =
                fakeProvider(maxPollWindowDays = 30, bookingHorizon = dayLimit(365)) { _, reservables, startDate, endDate ->
                    fetchedStart = startDate
                    fetchedEnd = endDate
                    batchFor(reservables, startDate, endDate, AvailabilityStatus.AVAILABLE)
                }
            val target = resolvedTarget("site:recgov:100", provider = provider, parentRef = ProviderRef.RecGov("100"))
            val composer = composer(listOf(target), availability = null)

            // An empty/short-lived batch would leave byRid empty and the mapping
            // step would throw NotFound after the window is already captured, so
            // the window assertions below still hold regardless.
            runCatching {
                composer.availabilityFor(
                    listOf(target.reservable),
                    LocalDate.parse("2026-08-12"),
                    LocalDate.parse("2026-08-19"),
                )
            }

            assertEquals(LocalDate.parse("2026-08-05"), fetchedStart)
            assertEquals(LocalDate.parse("2026-09-04"), fetchedEnd)
        }

    @Test
    fun `rejects a request wider than the vendor poll window`() =
        runBlocking {
            val provider = fakeProvider(maxPollWindowDays = 30, bookingHorizon = dayLimit(365))
            val target = resolvedTarget("site:recgov:100", provider = provider, parentRef = ProviderRef.RecGov("100"))
            val composer = composer(listOf(target), availability = null)

            assertFailsWith<AvailabilityServiceError.BadDateWindow.WindowTooLong> {
                composer.availabilityFor(
                    listOf(target.reservable),
                    LocalDate.parse("2026-08-12"),
                    LocalDate.parse("2026-10-02"), // 51 days > 30
                )
            }
            assertEquals(0, provider.catalogCalls, "an invalid window must short-circuit before any upstream fetch")
        }

    @Test
    fun `provider rate limit on live path rethrows instead of surfacing NotFound`() =
        runBlocking {
            // Regression test: PR 1's CatalogAvailabilityBatcher swallows
            // ReservationProviderError into a classified GroupFetchResult with a
            // null batch. On the live read path that must NOT become a 404 —
            // the old behavior (provider error propagates out of the composer so the
            // route maps it to 503) must be preserved.
            val provider = fakeProvider { _, _, _, _ -> throw ReservationProviderError.RateLimited(RuntimeException("429")) }
            val target = resolvedTarget("site:recgov:100", provider = provider, parentRef = ProviderRef.RecGov("100"))
            val composer = composer(listOf(target), availability = null)

            assertFailsWith<ReservationProviderError.RateLimited> {
                composer.availabilityFor(listOf(target.reservable), null, null)
            }
            assertEquals(1, provider.catalogCalls, "provider should be called exactly once before the error propagates")
        }

    // --- fixtures ---

    private fun composer(
        targets: Collection<ResolvedAvailabilityTarget>,
        availability: AvailabilityRepo?,
    ): ReservableAvailabilityComposer =
        ReservableAvailabilityComposer(
            targets = FakeTargetResolver(targets.associateBy { it.reservable.rid }),
            availability = availability,
            snapshotFreshnessTtl = { longTtl },
        )

    private fun reservable(rid: String): Reservable =
        Reservable(
            id = 0L,
            rid = ReservableId.parse(rid)!!,
            name = null,
            loop = null,
            siteType = null,
            raw = null,
        )

    /** Seeds a reservable row so the interval-table FK holds, returning its db id. */
    private fun seedReservable(rid: String): Long {
        val vendorId = ReservableId.parse(rid)!!.vendorId
        return ctx
            .fetchOne(
                """
                INSERT INTO reservables (type, vendor, vendor_id, source, name)
                VALUES ('site', 'recgov', ?, 'test', 'site')
                RETURNING id
                """.trimIndent(),
                vendorId,
            )!!
            .get("id", Long::class.java)
    }

    private fun resolvedTarget(
        rid: String,
        provider: ReservationProvider,
        parentRef: ProviderRef,
        parentPoiId: Long = 1L,
    ): ResolvedAvailabilityTarget =
        ResolvedAvailabilityTarget(
            reservable =
                Reservable(
                    id = seedReservable(rid),
                    rid = ReservableId.parse(rid)!!,
                    name = null,
                    loop = null,
                    siteType = null,
                    raw = null,
                ),
            provider = provider,
            parentRef = parentRef,
            parentPoiId = parentPoiId,
            dateContext = PoiDateContext(timeZone = ZoneOffset.UTC, earliestDate = earliest),
        )

    private fun fakeProvider(
        maxPollWindowDays: Int = 60,
        bookingHorizon: CapabilityLimit = dayLimit(180),
        onCatalog: CatalogAvailabilityHandler = { _, reservables, startDate, endDate ->
            batchFor(reservables, startDate, endDate, AvailabilityStatus.AVAILABLE)
        },
    ): FakeProvider = FakeProvider(maxPollWindowDays, bookingHorizon, onCatalog)

    private fun dayLimit(days: Int): CapabilityLimit = CapabilityLimit(days, ChronoUnit.DAYS)

    /** Build a batch that reports [status] for every requested reservable on every day in the window. */
    private fun batchFor(
        reservables: List<CatalogReservableRef>,
        startDate: LocalDate,
        endDate: LocalDate,
        status: AvailabilityStatus,
    ): AvailabilityObservationBatch {
        val observedAt = Instant.now()
        val dates = (0 until ChronoUnit.DAYS.between(startDate, endDate)).map { startDate.plusDays(it) }
        val observations =
            reservables.flatMap { ref ->
                dates.map { date -> ReservableDayObservation(ref.rid, date, observedAt, status) }
            }
        return AvailabilityObservationBatch(
            provider = "recgov",
            startDate = startDate,
            endDate = endDate,
            observations = observations,
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
        )
    }

    private class FakeTargetResolver(
        private val byRid: Map<ReservableId, ResolvedAvailabilityTarget>,
    ) : AvailabilityTargetResolver {
        override fun requireByRid(rid: ReservableId): ResolvedAvailabilityTarget = byRid[rid] ?: throw AvailabilityServiceError.NotFound

        override fun resolve(reservable: Reservable): ResolvedAvailabilityTarget? = byRid[reservable.rid]
    }

    private class FakeProvider(
        maxPollWindowDays: Int,
        bookingHorizon: CapabilityLimit,
        private val onCatalog: CatalogAvailabilityHandler,
    ) : ReservationProvider {
        var catalogCalls = 0

        override val id: ReservationProviderId = ReservationProviderId.RECGOV
        override val capabilities: ReservationProviderCapabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                maxPollWindowDays = maxPollWindowDays,
                bookingHorizon = bookingHorizon,
                fetchWindowCap = CapabilityLimit(maxPollWindowDays, ChronoUnit.DAYS),
            )

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("catalogAvailability is the batched path")

        override suspend fun catalogAvailability(
            ref: ProviderRef,
            reservables: List<CatalogReservableRef>,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch {
            catalogCalls++
            return onCatalog(ref, reservables, startDate, endDate)
        }
    }
}
