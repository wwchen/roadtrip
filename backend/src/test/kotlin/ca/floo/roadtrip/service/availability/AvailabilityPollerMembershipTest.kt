package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityPollerMembershipTest : SharedDbTest() {
    private lateinit var campsiteRepo: CampsiteRepo
    private lateinit var scopeResolver: WatchScopeResolver

    @BeforeAll
    fun setUp() {
        campsiteRepo = CampsiteRepo(ctx)
        scopeResolver = WatchScopeResolver(campsiteRepo)
    }

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now(ZoneOffset.UTC)

    private var poiSeq = 0

    private fun insertPoi(name: String = "Upper Pines"): Long {
        val sourceId = "poi-${poiSeq++}"
        return ctx
            .seedCatalogPoi(
                sourceId = sourceId,
                name = name,
                lon = -119.56,
                lat = 37.74,
                source = "test",
            ).poiId
    }

    private fun insertActiveWatch(
        poiId: Long? = null,
        campsiteId: Long? = null,
        startDate: String = "2026-07-04",
        endDate: String = "2026-12-31",
    ): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        start_date, end_date, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?::date, ?::date, 60, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    startDate,
                    endDate,
                )!!
                .get("id", Long::class.java)
        ctx.execute(
            "INSERT INTO availability_watch_target (watch_id, poi_id, campsite_id) VALUES (?, ?, ?)",
            watchId,
            poiId,
            campsiteId,
        )
        return watchId
    }

    private fun insertPausedWatch(poiId: Long): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        start_date, end_date, cadence_sec, trigger_kinds, status
                    ) VALUES (
                        '2026-07-04'::date, '2026-12-31'::date, 60, ARRAY['atc'], 'paused'
                    ) RETURNING id
                    """.trimIndent(),
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, poi_id) VALUES (?, ?)", watchId, poiId)
        return watchId
    }

    /** Inserts a `reservables` row linked to [poiId] and returns its surrogate id. */
    private fun insertCampsite(
        poiId: Long,
        vendorId: String,
    ): Long {
        val campgroundId = campgroundIdFor(poiId)
        return ctx.seedCampsite(
            campgroundId = campgroundId,
            vendor = "test",
            vendorId = vendorId,
            name = "Site $vendorId",
        )
    }

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

    private fun watch(id: Long): AvailabilityWatchRepo.Watch = AvailabilityWatchRepo(ctx).findById(id)!!

    /** Minimal AvailabilityProvider stub — only `id` is consumed by the membership. */
    private class FakeProvider(
        override val id: AvailabilityProviderId,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities = AvailabilityProviderCapabilities.UNSUPPORTED

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used by AvailabilityPollerMembershipTest")
    }

    private val fakeDateContext = PoiDateContext(timeZone = ZoneId.of("UTC"), earliestDate = LocalDate.now())

    /**
     * In-memory fake keyed by campsite id, so a test can control exactly
     * which (provider, parentRef, parentPoiId) each campsite resolves to
     * without seeding real provider-ref rows.
     */
    private class FakeTargetResolver : AvailabilityTargetResolver {
        val byCampsiteId = mutableMapOf<Long, ResolvedAvailabilityTarget>()

        fun stub(
            campsite: CampsiteAvailabilityTarget,
            provider: AvailabilityProviderId,
            parentRef: ProviderRef,
            parentPoiId: Long,
            dateContext: PoiDateContext,
        ) {
            byCampsiteId[campsite.id] =
                ResolvedAvailabilityTarget(
                    campsite = campsite,
                    provider = FakeProvider(provider),
                    parentRef = parentRef,
                    catalogRef =
                        CatalogCampsiteRef(
                            campsiteId = campsite.id,
                            vendorId = campsite.vendorId,
                        ),
                    parentPoiId = parentPoiId,
                    dateContext = dateContext,
                )
        }

        override fun resolve(campsite: CampsiteAvailabilityTarget): ResolvedAvailabilityTarget? = byCampsiteId[campsite.id]
    }

    @Test
    fun `two watches on same parentRef link to ONE poller`() {
        val poi = insertPoi()
        val campsiteA = insertCampsite(poi, "site-a")
        val campsiteB = insertCampsite(poi, "site-b")

        val targets = FakeTargetResolver()
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteA)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("232447"),
            poi,
            fakeDateContext,
        )
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteB)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("232447"),
            poi,
            fakeDateContext,
        )
        val membership = AvailabilityPollerMembership(scopeResolver, targets)
        val repo = AvailabilityPollerRepo(ctx)

        // watchA: POI-scope (expands to both reservables). watchB: reservable-scope under the same campground.
        val watchAId = insertActiveWatch(poiId = poi)
        val watchBId = insertActiveWatch(campsiteId = campsiteB)

        membership.sync(watch(watchAId), repo, null)
        membership.sync(watch(watchBId), repo, null)

        val a = repo.pollerIdsForWatch(watchAId)
        val b = repo.pollerIdsForWatch(watchBId)
        assertEquals(a, b)
        assertEquals(1, a.size)
    }

    @Test
    fun `two POIs sharing a parentRef produce ONE poller`() {
        // Two distinct POI rows (e.g. two campsites under the same campground)
        // resolving to the same vendor parentRef must coalesce to one poller.
        val poiX = insertPoi("Site Cluster X")
        val poiY = insertPoi("Site Cluster Y")
        val campsiteX = insertCampsite(poiX, "site-x")
        val campsiteY = insertCampsite(poiY, "site-y")

        val targets = FakeTargetResolver()
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteX)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("999000"),
            poiX,
            fakeDateContext,
        )
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteY)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("999000"),
            poiY,
            fakeDateContext,
        )
        val membership = AvailabilityPollerMembership(scopeResolver, targets)
        val repo = AvailabilityPollerRepo(ctx)

        val watchXId = insertActiveWatch(campsiteId = campsiteX)
        val watchYId = insertActiveWatch(campsiteId = campsiteY)

        membership.sync(watch(watchXId), repo, null)
        membership.sync(watch(watchYId), repo, null)

        val x = repo.pollerIdsForWatch(watchXId)
        val y = repo.pollerIdsForWatch(watchYId)
        assertEquals(x, y)
        assertEquals(1, x.size)
    }

    @Test
    fun `watch spanning two parentRefs links two pollers`() {
        val poi = insertPoi()
        val campsiteA = insertCampsite(poi, "site-a")
        val campsiteB = insertCampsite(poi, "site-b")

        val targets = FakeTargetResolver()
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteA)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("111111"),
            poi,
            fakeDateContext,
        )
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteB)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("222222"),
            poi,
            fakeDateContext,
        )
        val membership = AvailabilityPollerMembership(scopeResolver, targets)
        val repo = AvailabilityPollerRepo(ctx)

        // POI-scope watch expands to both reservables, which resolve to two distinct parentRefs.
        val watchId = insertActiveWatch(poiId = poi)

        membership.sync(watch(watchId), repo, null)

        assertEquals(2, repo.pollerIdsForWatch(watchId).size)
    }

    @Test
    fun `re-sync after target change drops the stale link`() {
        val poi = insertPoi()
        val campsiteA = insertCampsite(poi, "site-a")

        val targets = FakeTargetResolver()
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteA)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("333333"),
            poi,
            fakeDateContext,
        )
        val membership = AvailabilityPollerMembership(scopeResolver, targets)
        val repo = AvailabilityPollerRepo(ctx)

        val watchId = insertActiveWatch(campsiteId = campsiteA)
        membership.sync(watch(watchId), repo, null)
        val firstLinks = repo.pollerIdsForWatch(watchId)
        assertEquals(1, firstLinks.size)
        val firstPollerId = firstLinks.single()

        // Target set changes: same reservable now resolves to a different parentRef
        // (e.g. the campground's provider ref was corrected).
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteA)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("444444"),
            poi,
            fakeDateContext,
        )
        membership.sync(watch(watchId), repo, null)

        val secondLinks = repo.pollerIdsForWatch(watchId)
        assertEquals(1, secondLinks.size)
        assertTrue(firstPollerId !in secondLinks)
        // The stale poller lost its only link and should have been deactivated.
        assertEquals(false, repo.findById(firstPollerId)!!.active)
    }

    @Test
    fun `tighter cadence pull moves next_run_at earlier`() {
        val poi = insertPoi()
        val campsiteA = insertCampsite(poi, "site-a")

        val targets = FakeTargetResolver()
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteA)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("555555"),
            poi,
            fakeDateContext,
        )
        val membership = AvailabilityPollerMembership(scopeResolver, targets)
        val repo = AvailabilityPollerRepo(ctx)

        val watchId = insertActiveWatch(campsiteId = campsiteA)
        membership.sync(watch(watchId), repo, null)
        val pollerId = repo.pollerIdsForWatch(watchId).single()

        // Truncate to micros: Postgres TIMESTAMPTZ has microsecond resolution, so a
        // nanosecond-precision value can round UP on storage and break the `<=` below.
        val earlier = now().minusDays(1).truncatedTo(ChronoUnit.MICROS)
        membership.sync(watch(watchId), repo, tighterCadencePull = earlier)

        assertTrue(repo.findById(pollerId)!!.nextRunAt <= earlier)
    }

    @Test
    fun `non-ACTIVE watch clears its links and reaps the orphaned poller`() {
        val poi = insertPoi()
        val campsiteA = insertCampsite(poi, "site-a")

        val targets = FakeTargetResolver()
        targets.stub(
            campsiteRepo.findAvailabilityTargetById(campsiteA)!!,
            AvailabilityProviderId.RECGOV,
            ProviderRef.RecGov("666666"),
            poi,
            fakeDateContext,
        )
        val membership = AvailabilityPollerMembership(scopeResolver, targets)
        val repo = AvailabilityPollerRepo(ctx)

        val watchId = insertActiveWatch(campsiteId = campsiteA)
        membership.sync(watch(watchId), repo, null)
        val pollerId = repo.pollerIdsForWatch(watchId).single()

        val pausedWatchId = insertPausedWatch(poi)
        // Re-fetch: the paused watch has no campsite_id, but the sync should
        // short-circuit on status before ever resolving scope/targets.
        membership.sync(watch(pausedWatchId), repo, null)
        assertTrue(repo.pollerIdsForWatch(pausedWatchId).isEmpty())

        // Now pause the watch actually holding the link and re-sync it.
        ctx.execute("UPDATE availability_watch SET status = 'paused' WHERE id = ?", watchId)
        membership.sync(watch(watchId), repo, null)

        assertTrue(repo.pollerIdsForWatch(watchId).isEmpty())
        assertEquals(false, repo.findById(pollerId)!!.active)
    }
}
