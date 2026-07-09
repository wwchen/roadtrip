package ca.floo.roadtrip.service.scheduler

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
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
            ).poiId

    private fun seedReservable(
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
        val reservablesRepo = ReservableRepo(ctx)
        val registry = ReservationProviderRegistry(mapOf("test" to FakeProvider))
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                reservablesRepo = reservablesRepo,
                reservationProviders = registry,
                dateResolver = AvailabilityDateResolver(),
            )
        return AvailabilityPollerMembership(WatchScopeResolver(reservablesRepo), targets)
    }

    @Test
    fun `links an orphaned active watch and is a no-op on re-run`() {
        val poiId = seedPoi("232447")
        seedReservable(poiId, "100")
        val watchId = seedActiveWatch(poiId)
        val pollers = AvailabilityPollerRepo(ctx)
        // Orphaned: no links yet (V28 dropped the old job; nothing linked it).
        assertTrue(pollers.pollerIdsForWatch(watchId).isEmpty())

        val backfill = PollerBackfill(ctx, membership())
        backfill.run()

        // Linked to exactly one active poller.
        val linked = pollers.pollerIdsForWatch(watchId)
        assertEquals(1, linked.size)
        val pollerId = linked.single()
        assertTrue(pollers.findById(pollerId)!!.active)

        // Re-run is a no-op: same single link, same poller row (no duplicate poller).
        backfill.run()
        assertEquals(listOf(pollerId), pollers.pollerIdsForWatch(watchId))
        assertEquals(1, pollers.count(active = true))
    }

    private object FakeProvider : ReservationProvider {
        override val id = ReservationProviderId.RECGOV
        override val capabilities =
            ReservationProviderCapabilities(
                supportsAvailability = true,
                supportsAlerts = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }
}
