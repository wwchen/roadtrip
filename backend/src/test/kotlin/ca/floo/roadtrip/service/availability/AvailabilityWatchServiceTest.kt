package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.service.reservation.AvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderId
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityWatchServiceTest : SharedDbTest() {
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

    private fun service(): AvailabilityWatchService {
        val reservablesRepo = ReservableRepo(ctx)
        val registry = ReservationProviderRegistry(mapOf("test" to FakeProvider))
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                reservablesRepo = reservablesRepo,
                reservationProviders = registry,
                dateResolver = AvailabilityDateResolver(),
            )
        return AvailabilityWatchService(ctx, reservablesRepo, targets)
    }

    private fun poiInput(poiId: Long): AvailabilityWatchRepo.CreateInput =
        AvailabilityWatchRepo.CreateInput(
            targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, reservableId = null)),
            reservableFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = 60,
            triggerKinds = listOf("atc"),
            triggerConfig = JsonObject(emptyMap()),
            stopWhenTriggered = false,
        )

    @Test
    fun `a watch spanning two campgrounds links to two pollers`() {
        val poiA = seedPoi("232447")
        seedReservable(poiA, "100")
        val poiB = seedPoi("232999")
        seedReservable(poiB, "200")

        val svc = service()
        val watch =
            svc.create(
                AvailabilityWatchRepo.CreateInput(
                    targets =
                        listOf(
                            AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, reservableId = null),
                            AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, reservableId = null),
                        ),
                    reservableFilters = JsonObject(emptyMap()),
                    startDate = LocalDate.parse("2026-07-04"),
                    endDate = LocalDate.parse("2026-07-06"),
                    cadenceSec = 60,
                    triggerKinds = listOf("atc"),
                    triggerConfig = JsonObject(emptyMap()),
                    stopWhenTriggered = false,
                ),
            )

        val pollers = AvailabilityPollerRepo(ctx)
        val linked = pollers.pollerIdsForWatch(watch.id)
        assertEquals(2, linked.size)
        val parentRefs = linked.map { pollers.findById(it)!!.parentRef }.toSet()
        assertEquals(setOf("232447", "232999"), parentRefs)
    }

    @Test
    fun `create links an active watch to one poller`() {
        val poiId = seedPoi("232447")
        seedReservable(poiId, "100")
        val watch = service().create(poiInput(poiId))

        val pollers = AvailabilityPollerRepo(ctx)
        val linked = pollers.pollerIdsForWatch(watch.id)
        assertEquals(1, linked.size)
        val poller = pollers.findById(linked.single())!!
        assertTrue(poller.active)
        assertEquals("recgov", poller.provider)
        assertEquals("232447", poller.parentRef)
    }

    @Test
    fun `two watches on the same campground coalesce onto one poller`() {
        val poiId = seedPoi("232447")
        seedReservable(poiId, "100")
        val svc = service()
        val w1 = svc.create(poiInput(poiId))
        val w2 = svc.create(poiInput(poiId))

        val pollers = AvailabilityPollerRepo(ctx)
        val p1 = pollers.pollerIdsForWatch(w1.id).single()
        val p2 = pollers.pollerIdsForWatch(w2.id).single()
        assertEquals(p1, p2)
        assertEquals(1, pollers.count(active = true))
    }

    @Test
    fun `pausing a watch drops its links and deactivates the orphaned poller`() {
        val poiId = seedPoi("232447")
        seedReservable(poiId, "100")
        val svc = service()
        val watch = svc.create(poiInput(poiId))
        val pollers = AvailabilityPollerRepo(ctx)
        val pollerId = pollers.pollerIdsForWatch(watch.id).single()

        svc.update(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))

        assertTrue(pollers.pollerIdsForWatch(watch.id).isEmpty())
        assertEquals(false, pollers.findById(pollerId)!!.active)
    }

    @Test
    fun `deleting the last watch deactivates its poller`() {
        val poiId = seedPoi("232447")
        seedReservable(poiId, "100")
        val svc = service()
        val watch = svc.create(poiInput(poiId))
        val pollers = AvailabilityPollerRepo(ctx)
        val pollerId = pollers.pollerIdsForWatch(watch.id).single()

        assertTrue(svc.delete(watch.id))

        // Cascade dropped the link; the now-orphaned poller is deactivated (dormant, not deleted).
        assertTrue(pollers.watchIdsForPoller(pollerId).isEmpty())
        assertEquals(false, pollers.findById(pollerId)!!.active)
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
