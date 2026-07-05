package ca.floo.roadtrip.service.scheduler

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PollerBackfillTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_watch_target")
        ctx.execute("DELETE FROM availability_watch_poller")
        ctx.execute("DELETE FROM availability_poller")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private fun seedPoi(campgroundId: String): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', ?, 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, ?::jsonb, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
                "poi-$campgroundId",
                """{"recgov_id": "$campgroundId"}""",
            )!!
            .get("id", Long::class.java)

    private fun seedReservable(
        poiId: Long,
        siteId: String,
    ): Long {
        val id =
            ctx
                .fetchOne(
                    """
                    INSERT INTO reservables (type, vendor, vendor_id, name, source)
                    VALUES ('site', 'recgov', ?, ?, 'test')
                    RETURNING id
                    """.trimIndent(),
                    siteId,
                    "Site $siteId",
                )!!
                .get("id", Long::class.java)
        ctx.execute("INSERT INTO reservable_pois (reservable_id, poi_id) VALUES (?, ?)", id, poiId)
        return id
    }

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

        override suspend fun availability(req: AvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")

        override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")

        override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityObservationBatch =
            throw UnsupportedOperationException("not used")
    }
}
