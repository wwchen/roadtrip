package ca.floo.roadtrip.service.scheduler

import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val fakeProvider = FakeAvailabilityProvider(BookingProvider.RECGOV)

class PollerBackfillTest : SharedDbTest() {
    private var userSeq = 0

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seedOwner(): Long =
        ca.floo.roadtrip.repo
            .UserRepo(ctx)
            .create(
                email = "owner-${userSeq++}@example.com",
                displayName = null,
                isEmailVerified = true,
            ).id.value

    private fun seedPoi(campgroundId: String): Long =
        ctx
            .seedCatalogPoi(
                sourceId = "poi-$campgroundId",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "recgov",
                providerRefJson = """{"recgov_id": "$campgroundId"}""",
                bookingProvider = "recgov",
                bookingProviderRef = campgroundId,
            ).poiId

    private fun seedCampsite(
        poiId: Long,
        siteId: String,
    ): Long =
        ctx.seedCampsite(
            campgroundId = campgroundIdFor(poiId),
            vendor = "recgov",
            vendorId = siteId,
            name = "Site $siteId",
        )

    private fun campgroundIdFor(poiId: Long): Long =
        ctx
            .fetchOne("SELECT campground_id FROM poi_campgrounds WHERE poi_id = ?", poiId)!!
            .get("campground_id", Long::class.java)

    private fun seedActiveWatch(poiId: Long): Long {
        val ownerId = seedOwner()
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (owner_user_id, start_date, end_date, cadence_sec, trigger_kinds)
                    VALUES (?, '2026-07-04'::date, '2026-07-06'::date, 60, ARRAY['atc'])
                    RETURNING id
                    """.trimIndent(),
                    ownerId,
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, poi_id) VALUES (?, ?)", watchId, poiId)
        return watchId
    }

    private fun membership(): AvailabilityPollerMembership {
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = CampgroundRepo(ctx),
                availabilityProviders = listOf(fakeProvider),
                dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        return AvailabilityPollerMembership(WatchScopeResolver(campsitesRepo), targets)
    }

    @Test
    fun `links an orphaned active watch and is a no-op on re-run`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val watchId = seedActiveWatch(poiId)
        val pollerRepo = AvailabilityPollerRepo(ctx)
        // Orphaned: no links yet (V28 dropped the old job; nothing linked it).
        assertTrue(pollerRepo.pollerIdsForWatch(watchId).isEmpty())

        val backfill = PollerBackfill(ctx, membership())
        backfill.run()

        // Linked to exactly one active poller.
        val linked = pollerRepo.pollerIdsForWatch(watchId)
        assertEquals(1, linked.size)
        val pollerId = linked.single()
        assertTrue(pollerRepo.findById(pollerId)!!.active)

        // Re-run is a no-op: same single link, same poller row (no duplicate poller).
        backfill.run()
        assertEquals(listOf(pollerId), pollerRepo.pollerIdsForWatch(watchId))
        assertEquals(1, pollerRepo.count(active = true))
    }
}
