package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.alert.AlertProvider
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import ca.floo.roadtrip.service.availability.alert.InternalPollerAlertProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import kotlinx.serialization.json.JsonObject
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AvailabilityWatchServiceTest : SharedDbTest() {
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

    private fun service(alertProviders: AlertProviderRegistry? = null): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val registry = AvailabilityProviderRegistry(mapOf("test" to FakeProvider))
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                campsitesRepo = campsitesRepo,
                availabilityProviders = registry,
                dateResolver = AvailabilityDateResolver(),
            )
        val providers =
            alertProviders ?: AlertProviderRegistry(
                listOf(
                    InternalPollerAlertProvider(
                        AvailabilityPollerMembership(WatchScopeResolver(campsitesRepo), targets),
                    ),
                ),
            )
        return AvailabilityWatchService(ctx, providers)
    }

    private fun poiInput(poiId: Long): AvailabilityWatchRepo.CreateInput =
        AvailabilityWatchRepo.CreateInput(
            targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, campsiteId = null)),
            campsiteFilters = JsonObject(emptyMap()),
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
        seedCampsite(poiA, "100")
        val poiB = seedPoi("232999")
        seedCampsite(poiB, "200")

        val svc = service()
        val watch =
            svc.create(
                AvailabilityWatchRepo.CreateInput(
                    targets =
                        listOf(
                            AvailabilityWatchTargetRepo.TargetInput(poiId = poiA, campsiteId = null),
                            AvailabilityWatchTargetRepo.TargetInput(poiId = poiB, campsiteId = null),
                        ),
                    campsiteFilters = JsonObject(emptyMap()),
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
        seedCampsite(poiId, "100")
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
        seedCampsite(poiId, "100")
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
        seedCampsite(poiId, "100")
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
        seedCampsite(poiId, "100")
        val svc = service()
        val watch = svc.create(poiInput(poiId))
        val pollers = AvailabilityPollerRepo(ctx)
        val pollerId = pollers.pollerIdsForWatch(watch.id).single()

        assertTrue(svc.delete(watch.id))

        // Cascade dropped the link; the now-orphaned poller is deactivated (dormant, not deleted).
        assertTrue(pollers.watchIdsForPoller(pollerId).isEmpty())
        assertEquals(false, pollers.findById(pollerId)!!.active)
    }

    @Test
    fun `watch lifecycle drives alert-provider hooks through the registry`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val recorder = RecordingAlertProvider()
        val svc = service(AlertProviderRegistry(listOf(recorder)))

        val watch = svc.create(poiInput(poiId))
        svc.update(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))
        svc.update(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.ACTIVE))
        svc.delete(watch.id)

        assertEquals(
            listOf(
                watch.id to AlertEvent.ACTIVATED, // create
                watch.id to AlertEvent.DEACTIVATED, // pause
                watch.id to AlertEvent.ACTIVATED, // resume
                watch.id to AlertEvent.DEACTIVATED, // delete
            ),
            recorder.events,
        )
    }

    private enum class AlertEvent { ACTIVATED, DEACTIVATED }

    /** Fake alert provider that records `(watch.id, event)` tuples so the test
     *  can assert the service dispatches watch-lifecycle events through the
     *  registry rather than reaching into poller state directly. Impersonates
     *  the internal poller id because the v1 registry always dispatches to it. */
    private class RecordingAlertProvider : AlertProvider {
        override val id: String = AlertProviderRegistry.INTERNAL_POLLER_ID
        override val hostsAlerts: Boolean = false
        val events: MutableList<Pair<Long, AlertEvent>> = mutableListOf()

        override fun onWatchActivated(
            txn: DSLContext,
            watch: AvailabilityWatchRepo.Watch,
        ) {
            events += watch.id to AlertEvent.ACTIVATED
        }

        override fun onWatchDeactivated(
            txn: DSLContext,
            watch: AvailabilityWatchRepo.Watch,
        ) {
            events += watch.id to AlertEvent.DEACTIVATED
        }
    }

    private object FakeProvider : AvailabilityProvider {
        override val id = AvailabilityProviderId.RECGOV
        override val capabilities =
            AvailabilityProviderCapabilities(
                supportsAvailability = true,
                pollableForAlerts = true,
                bookingHorizonDays = 180,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }
}
