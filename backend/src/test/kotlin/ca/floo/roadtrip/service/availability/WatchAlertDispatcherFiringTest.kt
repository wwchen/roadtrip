package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.shippedTenantRegistry
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CellTransition
import ca.floo.roadtrip.model.availability.WatchStatus
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.auth.MagicLinkTokenService
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import ca.floo.roadtrip.service.poi.campground.CampgroundCta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals

private const val FIRE_DELAY_MS = 50L
private const val CONCURRENT_DISPATCHES = 4

class WatchAlertDispatcherFiringTest : SharedDbTest() {
    private val fired = ConcurrentLinkedQueue<Long>()
    private val night: LocalDate = LocalDate.now().plusDays(30)
    private var campsiteId = 0L
    private var ownerId = 0L
    private val opened get() = CellTransition(campsiteId, night, AvailabilityStatus.AVAILABLE)

    private val countingHandler =
        object : TriggerActionHandler {
            override val kinds: Set<String> = setOf(AvailabilityTriggerKinds.EMAIL_NOTIFY)

            override suspend fun fire(
                watch: AvailabilityWatchRepo.Watch,
                openings: List<TriggerOpening>,
            ): Boolean {
                delay(FIRE_DELAY_MS)
                fired += watch.id
                return true
            }
        }

    private val silentNotifications =
        object : NotificationSender {
            override suspend fun sendWatchStatus(
                notice: WatchStatusNotice,
                targets: List<NotificationTarget>,
            ): Boolean = true

            override suspend fun sendWatchOpenings(
                watchId: Long,
                startDate: LocalDate,
                endDate: LocalDate,
                openings: List<WatchOpening>,
                targets: List<NotificationTarget>,
                appRootUrl: String?,
            ): Boolean = true
        }

    @BeforeEach
    fun seed() {
        ctx.cleanCanonicalCatalogFixtures()
        fired.clear()
        ownerId =
            UserRepo(ctx).create(email = "firing-${System.nanoTime()}@example.com", displayName = null, isEmailVerified = true).id.value
        val fixture = ctx.seedCatalogPoi(sourceId = "firing-poi", name = "Firing CG", lon = -119.56, lat = 37.74)
        campsiteId = ctx.seedCampsite(campgroundId = fixture.catalogId, vendorId = "firing-site")
    }

    private fun dispatcher(): WatchAlertDispatcher {
        val campsitesRepo = CampsiteRepo(ctx)
        val watchRepo = AvailabilityWatchRepo(ctx)
        return WatchAlertDispatcher(
            notifications = silentNotifications,
            scopeResolver = WatchScopeResolver(campsitesRepo),
            watchRepo = watchRepo,
            targetResolver =
                WatchNotificationTargetResolver(
                    userSettingsRepo = UserSettingsRepo(ctx),
                    userRepo = UserRepo(ctx),
                    cipher = null,
                    magicLinkTokenService = MagicLinkTokenService(watchRepo),
                    appRootUrl = null,
                ),
            targets =
                DbAvailabilityTargetResolver(
                    poiRepo = PoiRepo(ctx),
                    campsitesRepo = campsitesRepo,
                    campgroundRepo = CampgroundRepo(ctx),
                    availabilityProviders = emptyList(),
                    dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
                    pollerRepo = AvailabilityPollerRepo(ctx),
                ),
            poiRepo = PoiServingRepo(ctx, enabledDataProviders = emptySet()),
            availabilityRepo = AvailabilityRepo(ctx),
            triggerActions = TriggerActionRegistry(listOf(countingHandler)),
            grafanaRootUrl = null,
            appRootUrl = null,
            campgroundCta = CampgroundCta(shippedTenantRegistry()),
        )
    }

    private fun watch(stopWhenTriggered: Boolean = true): AvailabilityWatchRepo.Watch =
        AvailabilityWatchRepo(ctx).create(
            AvailabilityWatchRepo.CreateInput(
                targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = null, campsiteId = campsiteId)),
                campsiteFilters = JsonObject(emptyMap()),
                startDate = night,
                endDate = night.plusDays(1),
                cadenceSec = null,
                triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                triggerConfig = JsonObject(emptyMap()),
                stopWhenTriggered = stopWhenTriggered,
                ownerUserId = ownerId,
            ),
        )

    @Test
    fun `a stop-when-triggered watch dispatched from two paths at once fires once`() {
        val watch = watch()
        val dispatcher = dispatcher()
        runBlocking(Dispatchers.Default) {
            (0 until CONCURRENT_DISPATCHES).map { async { dispatcher.dispatch(listOf(watch), listOf(opened)) } }.awaitAll()
        }
        assertEquals(listOf(watch.id), fired.toList())
        assertEquals(WatchStatus.DONE, AvailabilityWatchRepo(ctx).findById(watch.id)?.status)
    }

    @Test
    fun `a watch loaded active but done since is not fired`() {
        val watch = watch()
        ctx.execute("UPDATE availability_watch SET status = ? WHERE id = ?", WatchStatus.DONE.wireValue, watch.id)
        runBlocking { dispatcher().dispatch(listOf(watch), listOf(opened)) }
        assertEquals(emptyList(), fired.toList())
    }

    @Test
    fun `reader transitions reach watches created before the write, not after`() {
        val recordedAt = Instant.parse("2026-11-07T12:00:00Z")
        val before = watch(stopWhenTriggered = false)
        val after = watch(stopWhenTriggered = false)
        ctx.execute(
            "UPDATE availability_watch SET created_at = ?::timestamptz WHERE id = ?",
            recordedAt.minusSeconds(60).toString(),
            before.id,
        )
        ctx.execute(
            "UPDATE availability_watch SET created_at = ?::timestamptz WHERE id = ?",
            recordedAt.plusSeconds(60).toString(),
            after.id,
        )
        runBlocking { dispatcher().dispatchCovering(listOf(opened), recordedAt) }
        assertEquals(listOf(before.id), fired.toList())
    }

    @Test
    fun `reader transitions with nothing bookable fire nothing`() {
        watch(stopWhenTriggered = false)
        runBlocking {
            dispatcher().dispatchCovering(listOf(CellTransition(campsiteId, night, AvailabilityStatus.RESERVED)), Instant.now())
        }
        assertEquals(emptyList(), fired.toList())
    }
}
