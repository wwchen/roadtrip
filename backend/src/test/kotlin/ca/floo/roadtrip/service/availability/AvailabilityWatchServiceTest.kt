package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.FakeAvailabilityProvider
import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
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
import ca.floo.roadtrip.service.availability.alert.AlertProvider
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import ca.floo.roadtrip.service.availability.alert.InternalPollerAlertProvider
import ca.floo.roadtrip.service.availability.alert.WatchAlertScope
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.booking.BookingAdapter
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val fakeProvider = FakeAvailabilityProvider(BookingProvider.RECGOV)
private val nonPollableProvider = FakeAvailabilityProvider(BookingProvider.RECGOV, supportsInternalPolling = false)

class AvailabilityWatchServiceTest : SharedDbTest() {
    private val testOwner: ca.floo.roadtrip.model.domain.auth.UserId by lazy {
        UserRepo(ctx).create(email = "test-owner@example.com", displayName = null, isEmailVerified = true).id
    }

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

    private fun service(
        alertProviders: AlertProviderRegistry? = null,
        capabilityValidator: WatchCapabilityValidator = WatchCapabilityValidator { },
        availabilityProvider: AvailabilityProvider = fakeProvider,
        lifecycleNotifications: WatchLifecycleNotifications = ignoredLifecycleNotifications(),
    ): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = CampgroundRepo(ctx),
                availabilityProviders = listOf(availabilityProvider),
                dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        val providers =
            alertProviders ?: AlertProviderRegistry(
                listOf(
                    InternalPollerAlertProvider(
                        AvailabilityPollerMembership(WatchScopeResolver(campsitesRepo), targets),
                    ),
                ),
            )
        return AvailabilityWatchService(ctx, providers, capabilityValidator, lifecycleNotifications)
    }

    private fun bookingValidatedService(
        bookingProviders: BookingAdapterRegistry,
        availabilityProvider: AvailabilityProvider = fakeProvider,
        notificationTriggerKinds: List<String> =
            listOf(
                AvailabilityTriggerKinds.SLACK_NOTIFY,
                AvailabilityTriggerKinds.EMAIL_NOTIFY,
            ),
        recgovConfigured: Boolean = true,
    ): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = CampgroundRepo(ctx),
                availabilityProviders = listOf(availabilityProvider),
                dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        val scopeResolver = WatchScopeResolver(campsitesRepo)
        val providers =
            AlertProviderRegistry(
                listOf(
                    InternalPollerAlertProvider(
                        AvailabilityPollerMembership(scopeResolver, targets),
                    ),
                ),
            )
        return AvailabilityWatchService(
            ctx = ctx,
            alertProviders = providers,
            capabilityValidator =
                WatchTriggerCapabilityValidator(
                    scopeResolver = scopeResolver,
                    watchCapabilityService =
                        WatchCapabilityService(
                            availabilityTargets = targets,
                            bookingTargets = AvailabilityBookingTargetResolver(bookingProviders),
                            notificationTriggerKinds = notificationTriggerKinds,
                            recgovCredentials = { recgovConfigured },
                        ),
                ),
            lifecycleNotifications = ignoredLifecycleNotifications(),
        )
    }

    private fun ignoredLifecycleNotifications(): WatchLifecycleNotifications =
        object : WatchLifecycleNotifications {
            override fun afterCreate(watch: AvailabilityWatchRepo.Watch) = Unit

            override fun afterUpdate(
                before: AvailabilityWatchRepo.Watch,
                after: AvailabilityWatchRepo.Watch,
            ) = Unit

            override fun afterDelete(watch: AvailabilityWatchRepo.Watch) = Unit
        }

    private fun dispatchingLifecycleNotifications(notifications: NotificationSender): WatchLifecycleNotifications {
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
        val targetResolver =
            WatchNotificationTargetResolver(
                userSettingsRepo = UserSettingsRepo(ctx),
                userRepo = UserRepo(ctx),
                cipher = null,
                magicLinkTokenService = MagicLinkTokenService(AvailabilityWatchRepo(ctx)),
                appRootUrl = null,
            )
        val dispatcher =
            WatchAlertDispatcher(
                notifications = notifications,
                scopeResolver = WatchScopeResolver(campsitesRepo),
                watchRepo = AvailabilityWatchRepo(ctx),
                targetResolver = targetResolver,
                targets = targets,
                poiRepo = PoiServingRepo(ctx, enabledDataProviders = emptySet()),
                availabilityRepo = AvailabilityRepo(ctx),
                triggerActions =
                    TriggerActionRegistry(
                        listOf(NotifyTriggerActionHandler(notifications, targetResolver, appRootUrl = null)),
                    ),
                grafanaRootUrl = null,
                appRootUrl = null,
            )
        return DispatchingWatchLifecycleNotifications(
            dispatcher = dispatcher,
            scope = CoroutineScope(Dispatchers.Unconfined),
        )
    }

    private fun poiInput(
        poiId: Long,
        triggerKinds: List<String> = listOf("atc"),
        triggerConfig: JsonObject = JsonObject(emptyMap()),
        ownerUserId: ca.floo.roadtrip.model.domain.auth.UserId = testOwner,
    ): AvailabilityWatchRepo.CreateInput =
        AvailabilityWatchRepo.CreateInput(
            ownerUserId = ownerUserId.value,
            targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, campsiteId = null)),
            campsiteFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = 60,
            triggerKinds = triggerKinds,
            triggerConfig = triggerConfig,
            stopWhenTriggered = false,
        )

    private fun emailTriggerConfig(to: String): JsonObject =
        JsonObject(
            mapOf(
                AvailabilityTriggerKinds.EMAIL_NOTIFY to
                    JsonObject(
                        mapOf("to" to JsonPrimitive(to)),
                    ),
            ),
        )

    private fun AvailabilityWatchService.createForTest(
        input: AvailabilityWatchRepo.CreateInput,
        ownerUserId: ca.floo.roadtrip.model.domain.auth.UserId = testOwner,
    ) = create(
        ownerUserId = ownerUserId,
        targets = input.targets,
        campsiteFilters = input.campsiteFilters,
        startDate = input.startDate,
        endDate = input.endDate,
        cadenceSec = input.cadenceSec,
        triggerKinds = input.triggerKinds,
        triggerConfig = input.triggerConfig,
        stopWhenTriggered = input.stopWhenTriggered,
    )

    private fun AvailabilityWatchService.updateForTest(
        id: Long,
        input: AvailabilityWatchRepo.UpdateInput,
    ) = update(
        id = id,
        targets = input.targets,
        campsiteFilters = input.campsiteFilters,
        startDate = input.startDate,
        endDate = input.endDate,
        cadenceSec = input.cadenceSec,
        triggerKinds = input.triggerKinds,
        triggerConfig = input.triggerConfig,
        stopWhenTriggered = input.stopWhenTriggered,
        status = input.status,
    )

    @Test
    fun `create stamps the owner`() {
        val poiId =
            ctx
                .seedCatalogPoi(
                    sourceId = "svc-owner",
                    name = "Owner",
                    lon = -119.56,
                    lat = 37.74,
                    source = "recgov",
                    providerRefJson = """{"recgov_id": "999999"}""",
                    bookingProvider = "recgov",
                    bookingProviderRef = "999999",
                ).poiId
        ctx.seedCampsite(
            campgroundId = campgroundIdFor(poiId),
            vendor = "recgov",
            vendorId = "999",
            name = "Site 999",
        )
        val watch =
            service().create(
                ownerUserId = testOwner,
                targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, campsiteId = null)),
                campsiteFilters = kotlinx.serialization.json.JsonObject(emptyMap()),
                startDate = java.time.LocalDate.parse("2026-07-04"),
                endDate = java.time.LocalDate.parse("2026-07-06"),
                cadenceSec = 60,
                triggerKinds = listOf("atc"),
                triggerConfig = kotlinx.serialization.json.JsonObject(emptyMap()),
                stopWhenTriggered = false,
            )
        assertEquals(testOwner.value, watch.ownerUserId)
    }

    @Test
    fun `a watch spanning two campgrounds links to two pollerRepo`() {
        val poiA = seedPoi("232447")
        seedCampsite(poiA, "100")
        val poiB = seedPoi("232999")
        seedCampsite(poiB, "200")

        val svc = service()
        val watch =
            svc.createForTest(
                AvailabilityWatchRepo.CreateInput(
                    ownerUserId = testOwner.value,
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

        val pollerRepo = AvailabilityPollerRepo(ctx)
        val linked = pollerRepo.pollerIdsForWatch(watch.id)
        assertEquals(2, linked.size)
        val parentRefs = linked.map { pollerRepo.findById(it)!!.parentRef }.toSet()
        assertEquals(setOf("232447", "232999"), parentRefs)
    }

    @Test
    fun `create links an active watch to one poller`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val watch = service().createForTest(poiInput(poiId))

        val pollerRepo = AvailabilityPollerRepo(ctx)
        val linked = pollerRepo.pollerIdsForWatch(watch.id)
        assertEquals(1, linked.size)
        val poller = pollerRepo.findById(linked.single())!!
        assertTrue(poller.active)
        assertEquals("recgov", poller.provider)
        assertEquals("232447", poller.parentRef)
    }

    @Test
    fun `create rejects an atc watch when no booking provider supports its scoped campsite`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = bookingValidatedService(BookingAdapterRegistry(emptyList()))

        val error = assertFailsWith<AvailabilityWatchValidationException> { svc.createForTest(poiInput(poiId)) }

        assertEquals("unsupported_trigger", error.error)
        assertEquals(0, AvailabilityWatchRepo(ctx).count())
    }

    @Test
    fun `create accepts email notify without watch-level config`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")

        val watch =
            service().createForTest(
                poiInput(
                    poiId,
                    triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                ),
            )

        assertEquals(listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY), watch.triggerKinds)
        assertEquals(JsonObject(emptyMap()), watch.triggerConfig)
    }

    @Test
    fun `create rejects email notify when email transport is not configured`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc =
            bookingValidatedService(
                bookingProviders = BookingAdapterRegistry(emptyList()),
                notificationTriggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY),
            )

        val error =
            assertFailsWith<AvailabilityWatchValidationException> {
                svc.createForTest(
                    poiInput(
                        poiId,
                        triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                        triggerConfig = emailTriggerConfig("alerts@example.test"),
                    ),
                )
            }

        assertEquals("unsupported_trigger", error.error)
        assertEquals(0, AvailabilityWatchRepo(ctx).count())
    }

    @Test
    fun `update rejects adding atc when no booking provider supports the scoped campsite`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = bookingValidatedService(BookingAdapterRegistry(emptyList()))
        val watch = svc.createForTest(poiInput(poiId, triggerKinds = listOf("slack_notify")))

        val error =
            assertFailsWith<AvailabilityWatchValidationException> {
                svc.updateForTest(watch.id, AvailabilityWatchRepo.UpdateInput(triggerKinds = listOf("atc")))
            }

        assertEquals("unsupported_trigger", error.error)
        assertEquals(listOf("slack_notify"), AvailabilityWatchRepo(ctx).findById(watch.id)!!.triggerKinds)
    }

    @Test
    fun `update rejects invalid trigger config and leaves stored config unchanged`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = service()
        val watch = svc.createForTest(poiInput(poiId, triggerKinds = listOf("slack_notify")))

        val error =
            assertFailsWith<AvailabilityWatchValidationException> {
                svc.updateForTest(
                    watch.id,
                    AvailabilityWatchRepo.UpdateInput(
                        triggerConfig =
                            JsonObject(
                                mapOf(
                                    "slack_notify" to JsonObject(mapOf("channel" to JsonPrimitive(""))),
                                ),
                            ),
                    ),
                )
            }

        assertEquals("invalid_trigger_config", error.error)
        assertEquals(JsonObject(emptyMap()), AvailabilityWatchRepo(ctx).findById(watch.id)!!.triggerConfig)
    }

    @Test
    fun `update accepts adding email notify without watch-level config`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = service()
        val watch = svc.createForTest(poiInput(poiId, triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY)))

        val updated =
            svc.updateForTest(
                watch.id,
                AvailabilityWatchRepo.UpdateInput(
                    triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                ),
            )

        assertEquals(listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY), updated?.triggerKinds)
        assertEquals(JsonObject(emptyMap()), updated?.triggerConfig)
    }

    @Test
    fun `update allows pausing an unsupported legacy atc watch`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val watch = service().createForTest(poiInput(poiId))
        val validatingService = bookingValidatedService(BookingAdapterRegistry(emptyList()))

        val updated = validatingService.updateForTest(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))

        assertEquals(WatchStatus.PAUSED, updated?.status)
    }

    @Test
    fun `create allows an atc watch when recgov booking provider supports its scoped campsite`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = bookingValidatedService(BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider)))

        val watch = svc.createForTest(poiInput(poiId))

        assertEquals(listOf("atc"), watch.triggerKinds)
        assertEquals(1, AvailabilityPollerRepo(ctx).pollerIdsForWatch(watch.id).size)
    }

    @Test
    fun `create rejects an atc watch from an owner with no rec_gov credentials`() {
        // The write-time gate is the authoritative one: the capability block
        // already hides `atc` from this user, but nothing stops a direct POST.
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc =
            bookingValidatedService(
                bookingProviders = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider)),
                recgovConfigured = false,
            )

        val error = assertFailsWith<AvailabilityWatchValidationException> { svc.createForTest(poiInput(poiId)) }

        assertEquals("unsupported_trigger", error.error)
        assertEquals(0, AvailabilityWatchRepo(ctx).count())
    }

    @Test
    fun `create rejects slack watch when provider does not support internal polling`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc =
            bookingValidatedService(
                bookingProviders = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider)),
                availabilityProvider = nonPollableProvider,
            )

        val error =
            assertFailsWith<AvailabilityWatchValidationException> {
                svc.createForTest(
                    poiInput(
                        poiId,
                        triggerKinds = listOf("slack_notify"),
                    ),
                )
            }

        assertEquals("unsupported_trigger", error.error)
        assertEquals(0, AvailabilityWatchRepo(ctx).count())
    }

    @Test
    fun `create rejects atc watch when provider does not support internal polling`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc =
            bookingValidatedService(
                bookingProviders = BookingAdapterRegistry(listOf(RecGovOnlyBookingProvider)),
                availabilityProvider = nonPollableProvider,
            )

        val error = assertFailsWith<AvailabilityWatchValidationException> { svc.createForTest(poiInput(poiId)) }

        assertEquals("unsupported_trigger", error.error)
        assertEquals(0, AvailabilityWatchRepo(ctx).count())
    }

    @Test
    fun `create does not link a poller when provider does not support internal polling`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val watch = service(availabilityProvider = nonPollableProvider).createForTest(poiInput(poiId))

        assertEquals(emptyList<Long>(), AvailabilityPollerRepo(ctx).pollerIdsForWatch(watch.id))
    }

    @Test
    fun `two watches on the same campground coalesce onto one poller`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = service()
        val w1 = svc.createForTest(poiInput(poiId))
        val w2 = svc.createForTest(poiInput(poiId))

        val pollerRepo = AvailabilityPollerRepo(ctx)
        val p1 = pollerRepo.pollerIdsForWatch(w1.id).single()
        val p2 = pollerRepo.pollerIdsForWatch(w2.id).single()
        assertEquals(p1, p2)
        assertEquals(1, pollerRepo.count(active = true))
    }

    @Test
    fun `pausing a watch drops its links and deactivates the orphaned poller`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = service()
        val watch = svc.createForTest(poiInput(poiId))
        val pollerRepo = AvailabilityPollerRepo(ctx)
        val pollerId = pollerRepo.pollerIdsForWatch(watch.id).single()

        svc.updateForTest(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))

        assertTrue(pollerRepo.pollerIdsForWatch(watch.id).isEmpty())
        assertEquals(false, pollerRepo.findById(pollerId)!!.active)
    }

    @Test
    fun `deleting the last watch deactivates its poller`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = service()
        val watch = svc.createForTest(poiInput(poiId))
        val pollerRepo = AvailabilityPollerRepo(ctx)
        val pollerId = pollerRepo.pollerIdsForWatch(watch.id).single()

        assertTrue(svc.delete(watch.id))

        // Cascade dropped the link; the now-orphaned poller is deactivated (dormant, not deleted).
        assertTrue(pollerRepo.watchIdsForPoller(pollerId).isEmpty())
        assertEquals(false, pollerRepo.findById(pollerId)!!.active)
    }

    @Test
    fun `watch lifecycle drives alert-provider hooks through the registry`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val recorder = RecordingAlertProvider()
        val svc = service(AlertProviderRegistry(listOf(recorder)))

        val watch = svc.createForTest(poiInput(poiId))
        svc.updateForTest(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))
        svc.updateForTest(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.ACTIVE))
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

    @Test
    fun `watch mutations emit lifecycle notification callbacks from the service path`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val lifecycle = RecordingLifecycleNotifications()
        val svc = service(lifecycleNotifications = lifecycle)

        val watch = svc.createForTest(poiInput(poiId))
        val paused = svc.updateForTest(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))!!
        val resumed = svc.updateForTest(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.ACTIVE))!!
        assertTrue(svc.delete(watch.id))

        assertEquals(
            listOf(
                watch.id to WatchStatus.ACTIVE,
            ),
            lifecycle.created.map { it.id to it.status },
        )
        assertEquals(
            listOf(
                WatchStatus.ACTIVE to WatchStatus.PAUSED,
                WatchStatus.PAUSED to WatchStatus.ACTIVE,
            ),
            lifecycle.updated.map { it.first.status to it.second.status },
        )
        assertEquals(WatchStatus.PAUSED, paused.status)
        assertEquals(WatchStatus.ACTIVE, resumed.status)
        assertEquals(listOf(watch.id), lifecycle.deleted.map { it.id })
    }

    @Test
    fun `create sends initial email status through the dispatching lifecycle hook`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val notifications = RecordingNotifications()
        val svc =
            service(
                lifecycleNotifications = dispatchingLifecycleNotifications(notifications),
            )

        val watch =
            svc.createForTest(
                poiInput(
                    poiId,
                    triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                    triggerConfig = emailTriggerConfig("alerts@example.test"),
                ),
            )

        val status = notifications.statuses.single()
        assertEquals(watch.id, status.notice.watchId)
        assertEquals(WatchStatusNotice.State.UNCHECKED, status.notice.state)
        assertEquals(listOf(NotificationTarget.Email(listOf("test-owner@example.com"))), status.targets)
    }

    @Test
    fun `delete returning snapshot uses the shared delete path and emits stopped lifecycle callback`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val lifecycle = RecordingLifecycleNotifications()
        val svc = service(lifecycleNotifications = lifecycle)
        val watch = svc.createForTest(poiInput(poiId))
        lifecycle.clear()

        val snapshot = svc.deleteReturningSnapshot(watch.id)

        assertEquals(watch.id, snapshot?.id)
        assertEquals(null, AvailabilityWatchRepo(ctx).findById(watch.id))
        assertEquals(listOf(watch.id), lifecycle.deleted.map { it.id })
    }

    private enum class AlertEvent { ACTIVATED, DEACTIVATED }

    private class RecordingLifecycleNotifications : WatchLifecycleNotifications {
        val created: MutableList<AvailabilityWatchRepo.Watch> = mutableListOf()
        val updated: MutableList<Pair<AvailabilityWatchRepo.Watch, AvailabilityWatchRepo.Watch>> = mutableListOf()
        val deleted: MutableList<AvailabilityWatchRepo.Watch> = mutableListOf()

        override fun afterCreate(watch: AvailabilityWatchRepo.Watch) {
            created += watch
        }

        override fun afterUpdate(
            before: AvailabilityWatchRepo.Watch,
            after: AvailabilityWatchRepo.Watch,
        ) {
            updated += before to after
        }

        override fun afterDelete(watch: AvailabilityWatchRepo.Watch) {
            deleted += watch
        }

        fun clear() {
            created.clear()
            updated.clear()
            deleted.clear()
        }
    }

    private class RecordingNotifications : NotificationSender {
        data class Status(
            val notice: WatchStatusNotice,
            val targets: List<NotificationTarget>,
        )

        val statuses: MutableList<Status> = mutableListOf()

        override suspend fun sendWatchStatus(
            notice: WatchStatusNotice,
            targets: List<NotificationTarget>,
        ): Boolean {
            statuses += Status(notice, targets)
            return true
        }

        override suspend fun sendWatchOpenings(
            watchId: Long,
            startDate: LocalDate,
            endDate: LocalDate,
            openings: List<WatchOpening>,
            targets: List<NotificationTarget>,
            appRootUrl: String?,
        ): Boolean = false
    }

    /** Fake alert provider that records `(watch.id, event)` tuples so the test
     *  can assert the service dispatches watch-lifecycle events through the
     *  registry rather than reaching into poller state directly. Impersonates
     *  the internal poller id because the v1 registry always dispatches to it. */
    private class RecordingAlertProvider : AlertProvider {
        override val id: String = AlertProviderRegistry.INTERNAL_POLLER_ID
        override val hostsAlerts: Boolean = false
        val events: MutableList<Pair<Long, AlertEvent>> = mutableListOf()

        override fun onWatchActivated(
            scope: WatchAlertScope,
            watch: AvailabilityWatchRepo.Watch,
        ) {
            events += watch.id to AlertEvent.ACTIVATED
        }

        override fun onWatchDeactivated(
            scope: WatchAlertScope,
            watch: AvailabilityWatchRepo.Watch,
        ) {
            events += watch.id to AlertEvent.DEACTIVATED
        }
    }

    private object RecGovOnlyBookingProvider : BookingAdapter {
        override val id: BookingProvider = BookingProvider.RECGOV

        override fun targetFor(
            parentRef: BookingProviderRef,
            campsiteId: Long,
            vendorSiteId: String,
        ): BookingTarget? {
            if (parentRef !is BookingProviderRef.RecGov) return null
            return BookingTarget(
                providerId = id,
                parentRef = parentRef,
                campsiteId = campsiteId,
                vendorSiteId = vendorSiteId,
            )
        }

        override fun can(
            action: BookingAction,
            target: BookingTarget,
        ): Boolean =
            action == BookingAction.ADD_TO_CART &&
                target.providerId == BookingProvider.RECGOV &&
                target.parentRef is BookingProviderRef.RecGov &&
                target.vendorSiteId.isNotBlank()

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult = AddToCartResult.Unsupported
    }
}
