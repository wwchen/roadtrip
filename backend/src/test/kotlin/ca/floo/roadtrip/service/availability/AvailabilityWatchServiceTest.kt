package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
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
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    private fun service(
        alertProviders: AlertProviderRegistry? = null,
        capabilityValidator: WatchCapabilityValidator = NoopWatchCapabilityValidator,
        availabilityProvider: AvailabilityProvider = FakeProvider,
    ): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val registry = AvailabilityProviderRegistry(mapOf("test" to availabilityProvider))
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
        return AvailabilityWatchService(ctx, providers, capabilityValidator)
    }

    private fun bookingValidatedService(
        bookingProviders: BookingProviderRegistry,
        availabilityProvider: AvailabilityProvider = FakeProvider,
        notificationTriggerKinds: List<String> =
            listOf(
                AvailabilityTriggerKinds.SLACK_NOTIFY,
                AvailabilityTriggerKinds.EMAIL_NOTIFY,
            ),
    ): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val registry = AvailabilityProviderRegistry(mapOf("test" to availabilityProvider))
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                campsitesRepo = campsitesRepo,
                availabilityProviders = registry,
                dateResolver = AvailabilityDateResolver(),
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
                    capabilities =
                        WatchCapabilityService(
                            availabilityTargets = targets,
                            bookingTargets = AvailabilityBookingTargetResolver(bookingProviders),
                            notificationTriggerKinds = notificationTriggerKinds,
                        ),
                ),
        )
    }

    private fun poiInput(
        poiId: Long,
        triggerKinds: List<String> = listOf("atc"),
        triggerConfig: JsonObject = JsonObject(emptyMap()),
    ): AvailabilityWatchRepo.CreateInput =
        AvailabilityWatchRepo.CreateInput(
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

    private fun AvailabilityWatchService.createForTest(input: AvailabilityWatchRepo.CreateInput) =
        create(
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
    fun `a watch spanning two campgrounds links to two pollers`() {
        val poiA = seedPoi("232447")
        seedCampsite(poiA, "100")
        val poiB = seedPoi("232999")
        seedCampsite(poiB, "200")

        val svc = service()
        val watch =
            svc.createForTest(
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
        val watch = service().createForTest(poiInput(poiId))

        val pollers = AvailabilityPollerRepo(ctx)
        val linked = pollers.pollerIdsForWatch(watch.id)
        assertEquals(1, linked.size)
        val poller = pollers.findById(linked.single())!!
        assertTrue(poller.active)
        assertEquals("recgov", poller.provider)
        assertEquals("232447", poller.parentRef)
    }

    @Test
    fun `create rejects an atc watch when no booking provider supports its scoped campsite`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = bookingValidatedService(BookingProviderRegistry(emptyList()))

        val error = assertFailsWith<AvailabilityWatchValidationException> { svc.createForTest(poiInput(poiId)) }

        assertEquals("unsupported_trigger", error.error)
        assertEquals(0, AvailabilityWatchRepo(ctx).count())
    }

    @Test
    fun `create rejects email notify without a to address`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")

        val error =
            assertFailsWith<AvailabilityWatchValidationException> {
                service().createForTest(
                    poiInput(
                        poiId,
                        triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                    ),
                )
            }

        assertEquals("invalid_trigger_config", error.error)
        assertEquals(0, AvailabilityWatchRepo(ctx).count())
    }

    @Test
    fun `create stores email notify to address`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")

        val watch =
            service().createForTest(
                poiInput(
                    poiId,
                    triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                    triggerConfig = emailTriggerConfig("alerts@example.test"),
                ),
            )

        assertEquals(listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY), watch.triggerKinds)
        assertEquals(
            "alerts@example.test",
            watch.triggerConfig[AvailabilityTriggerKinds.EMAIL_NOTIFY]!!
                .jsonObject["to"]!!
                .jsonPrimitive.content,
        )
    }

    @Test
    fun `create rejects email notify when email transport is not configured`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc =
            bookingValidatedService(
                bookingProviders = BookingProviderRegistry(emptyList()),
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
        val svc = bookingValidatedService(BookingProviderRegistry(emptyList()))
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
    fun `update rejects adding email notify without a to address`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = service()
        val watch = svc.createForTest(poiInput(poiId, triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY)))

        val error =
            assertFailsWith<AvailabilityWatchValidationException> {
                svc.updateForTest(
                    watch.id,
                    AvailabilityWatchRepo.UpdateInput(
                        triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                    ),
                )
            }

        assertEquals("invalid_trigger_config", error.error)
        assertEquals(listOf(AvailabilityTriggerKinds.SLACK_NOTIFY), AvailabilityWatchRepo(ctx).findById(watch.id)!!.triggerKinds)
    }

    @Test
    fun `update allows pausing an unsupported legacy atc watch`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val watch = service().createForTest(poiInput(poiId))
        val validatingService = bookingValidatedService(BookingProviderRegistry(emptyList()))

        val updated = validatingService.updateForTest(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))

        assertEquals(WatchStatus.PAUSED, updated?.status)
    }

    @Test
    fun `create allows an atc watch when recgov booking provider supports its scoped campsite`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = bookingValidatedService(BookingProviderRegistry(listOf(RecGovOnlyBookingProvider)))

        val watch = svc.createForTest(poiInput(poiId))

        assertEquals(listOf("atc"), watch.triggerKinds)
        assertEquals(1, AvailabilityPollerRepo(ctx).pollerIdsForWatch(watch.id).size)
    }

    @Test
    fun `create rejects slack watch when provider does not support internal polling`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc =
            bookingValidatedService(
                bookingProviders = BookingProviderRegistry(listOf(RecGovOnlyBookingProvider)),
                availabilityProvider = NonPollableProvider,
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
                bookingProviders = BookingProviderRegistry(listOf(RecGovOnlyBookingProvider)),
                availabilityProvider = NonPollableProvider,
            )

        val error = assertFailsWith<AvailabilityWatchValidationException> { svc.createForTest(poiInput(poiId)) }

        assertEquals("unsupported_trigger", error.error)
        assertEquals(0, AvailabilityWatchRepo(ctx).count())
    }

    @Test
    fun `create does not link a poller when provider does not support internal polling`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val watch = service(availabilityProvider = NonPollableProvider).createForTest(poiInput(poiId))

        assertEquals(emptyList<Long>(), AvailabilityPollerRepo(ctx).pollerIdsForWatch(watch.id))
    }

    @Test
    fun `two watches on the same campground coalesce onto one poller`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = service()
        val w1 = svc.createForTest(poiInput(poiId))
        val w2 = svc.createForTest(poiInput(poiId))

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
        val watch = svc.createForTest(poiInput(poiId))
        val pollers = AvailabilityPollerRepo(ctx)
        val pollerId = pollers.pollerIdsForWatch(watch.id).single()

        svc.updateForTest(watch.id, AvailabilityWatchRepo.UpdateInput(status = WatchStatus.PAUSED))

        assertTrue(pollers.pollerIdsForWatch(watch.id).isEmpty())
        assertEquals(false, pollers.findById(pollerId)!!.active)
    }

    @Test
    fun `deleting the last watch deactivates its poller`() {
        val poiId = seedPoi("232447")
        seedCampsite(poiId, "100")
        val svc = service()
        val watch = svc.createForTest(poiInput(poiId))
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
                supportsInternalPolling = true,
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

    private object NonPollableProvider : AvailabilityProvider {
        override val id = AvailabilityProviderId.RECGOV
        override val capabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = false,
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

    private object RecGovOnlyBookingProvider : BookingProvider {
        override val id: BookingProviderId = BookingProviderId.RECGOV

        override fun targetFor(
            parentRef: ProviderRef,
            campsiteRef: CatalogCampsiteRef,
        ): BookingTarget? {
            if (parentRef !is ProviderRef.RecGov) return null
            return BookingTarget(
                providerId = id,
                parentRef = parentRef,
                campsiteRef = campsiteRef,
            )
        }

        override fun can(
            action: BookingAction,
            target: BookingTarget,
        ): Boolean =
            action == BookingAction.ADD_TO_CART &&
                target.providerId == BookingProviderId.RECGOV &&
                target.parentRef is ProviderRef.RecGov &&
                target.campsiteRef.vendorId.isNotBlank()

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult = AddToCartResult.Unsupported
    }
}
