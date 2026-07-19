package ca.floo.roadtrip.service.scheduler

import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PollerBackfillTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
    }

    private fun seedPoi(campgroundId: String): Long =
        ctx
            .seedCatalogPoi(
                sourceId = "poi-$campgroundId",
                name = "Upper Pines",
                lon = -119.56,
                lat = 37.74,
                source = "test",
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
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (start_date, end_date, cadence_sec, trigger_kinds)
                    VALUES ('2026-07-04'::date, '2026-07-06'::date, 60, ARRAY['atc'])
                    RETURNING id
                    """.trimIndent(),
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO availability_watch_target (watch_id, poi_id) VALUES (?, ?)", watchId, poiId)
        return watchId
    }

    private fun membership(): AvailabilityPollerMembership {
        val campsitesRepo = CampsiteRepo(ctx)
        val registry = AvailabilityProviderRegistry(mapOf("recgov" to FakeProvider))
        val targets =
            DbAvailabilityTargetResolver(
                refResolver =
                    ca.floo.roadtrip.service.ref
                        .DbRefResolver(ctx),
                ctx = ctx,
                campsitesRepo = campsitesRepo,
                availabilityProviders = registry,
                dateResolver = AvailabilityDateResolver(),
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

    private object FakeProvider : AvailabilityProvider {
        override val id = BookingProvider.RECGOV
        override val capabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            ref: BookingProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }
}
