package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.availability.PoiDateContext
import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TriggerActionHandlerTest {
    @Test
    fun `NotifyTriggerActionHandler forwards slack channel override as notification target`() =
        runBlocking {
            val notifications = CapturingNotifications(result = true)
            val handler = NotifyTriggerActionHandler(notifications = notifications, appRootUrl = "https://app.example")

            val watch =
                fakeWatch(
                    id = 42L,
                    triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY),
                    triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("custom-channel"))),
                )
            val delivered = handler.fire(watch, openings = listOf(triggerOpening()))

            assertTrue(delivered)
            assertEquals(42L, notifications.lastWatchId)
            assertEquals(listOf(NotificationTarget.Slack("custom-channel")), notifications.lastTargets)
            assertEquals("https://app.example", notifications.lastAppRootUrl)
        }

    @Test
    fun `NotifyTriggerActionHandler prefers nested channel override over legacy flat channel`() =
        runBlocking {
            val notifications = CapturingNotifications(result = true)
            val handler = NotifyTriggerActionHandler(notifications = notifications, appRootUrl = "https://app.example")

            val delivered =
                handler.fire(
                    fakeWatch(
                        id = 42L,
                        triggerConfig =
                            JsonObject(
                                mapOf(
                                    "channel" to JsonPrimitive("legacy-channel"),
                                    AvailabilityTriggerKinds.SLACK_NOTIFY to
                                        JsonObject(
                                            mapOf("channel" to JsonPrimitive("nested-channel")),
                                        ),
                                ),
                            ),
                    ),
                    openings = listOf(triggerOpening()),
                )

            assertTrue(delivered)
            assertEquals(listOf(NotificationTarget.Slack("nested-channel")), notifications.lastTargets)
        }

    @Test
    fun `NotifyTriggerActionHandler omits channel when triggerConfig has none`() =
        runBlocking {
            val notifications = CapturingNotifications(result = true)
            val handler = NotifyTriggerActionHandler(notifications = notifications, appRootUrl = "https://app.example")

            val delivered =
                handler.fire(
                    fakeWatch(
                        id = 42L,
                        triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY),
                    ),
                    openings = listOf(triggerOpening()),
                )

            assertTrue(delivered)
            assertEquals(listOf(NotificationTarget.Slack()), notifications.lastTargets)
        }

    @Test
    fun `NotifyTriggerActionHandler combines slack and email targets into one send`() =
        runBlocking {
            val notifications = CapturingNotifications(result = true)
            val handler = NotifyTriggerActionHandler(notifications = notifications, appRootUrl = null)

            val delivered =
                handler.fire(
                    fakeWatch(
                        id = 42L,
                        triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.EMAIL_NOTIFY),
                        triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("#custom"))),
                    ),
                    openings = listOf(triggerOpening()),
                )

            assertTrue(delivered)
            assertEquals(
                listOf(NotificationTarget.Slack("#custom"), NotificationTarget.Email()),
                notifications.lastTargets,
            )
            assertEquals(1, notifications.openingSends)
        }

    @Test
    fun `NotifyTriggerActionHandler reports aggregate transport failure as false`() =
        runBlocking {
            val handler = NotifyTriggerActionHandler(notifications = CapturingNotifications(result = false), appRootUrl = null)

            assertFalse(handler.fire(fakeWatch(id = 9L), openings = listOf(triggerOpening())))
        }

    @Test
    fun `AtcTriggerActionHandler executes first supported opening through booking provider`() =
        runBlocking {
            val bookingProvider = RecordingBookingProvider()
            val bookingProviders = listOf<BookingProvider>(bookingProvider)
            val handler =
                AtcTriggerActionHandler(
                    bookings = bookingProviders,
                    bookingTargets = AvailabilityBookingTargetResolver(bookingProviders),
                    notifications = CapturingNotifications(result = true),
                )
            val watch = fakeWatch(id = 42L, triggerKinds = listOf(AtcTriggerActionHandler.KIND), stopWhenTriggered = true)

            val delivered = handler.fire(watch, openings = listOf(triggerOpening()))

            assertTrue(delivered)
            val request = bookingProvider.requests.single()
            assertEquals(42L, request.watchId)
            assertEquals(BookingProviderId.RECGOV, request.target.providerId)
            assertEquals(7L, request.target.campsiteRef.campsiteId)
            assertEquals("site-7", request.target.campsiteRef.vendorId)
            assertEquals(LocalDate.parse("2026-07-04"), request.arrivalDate)
            assertEquals(LocalDate.parse("2026-07-06"), request.checkoutDate)
            assertEquals("Site 12", request.campsiteLabel)
            assertTrue(request.stopWhenTriggered)
        }

    @Test
    fun `AtcTriggerActionHandler reports direct companion success and marks fired`() =
        runBlocking {
            val bookingProvider =
                RecordingBookingProvider(
                    resultFactory = { request: AddToCartRequest ->
                        AddToCartResult.Completed(
                            providerId = BookingProviderId.RECGOV,
                            request = buildJsonObject { put("watch_id", request.watchId) },
                            response =
                                buildJsonObject {
                                    put("ok", true)
                                    put("cart_added", true)
                                },
                        )
                    },
                )
            val bookingProviders = listOf<BookingProvider>(bookingProvider)
            val notifications = CapturingNotifications(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = bookingProviders,
                    bookingTargets = AvailabilityBookingTargetResolver(bookingProviders),
                    notifications = notifications,
                )
            val watch =
                fakeWatch(
                    id = 42L,
                    triggerKinds = listOf(AtcTriggerActionHandler.KIND),
                    triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("#custom"))),
                    stopWhenTriggered = true,
                )

            val delivered = handler.fire(watch, openings = listOf(triggerOpening()))

            assertTrue(delivered)
            val result = notifications.atcResults.single()
            assertEquals(42L, result.watchId)
            assertEquals("recgov", result.vendor)
            assertEquals("completed", result.status)
            assertEquals(listOf(NotificationTarget.Slack("#custom")), result.targets)
        }

    @Test
    fun `AtcTriggerActionHandler reports direct companion failure without marking fired`() =
        runBlocking {
            val bookingProvider =
                RecordingBookingProvider(
                    resultFactory = { request: AddToCartRequest ->
                        AddToCartResult.Failed(
                            providerId = BookingProviderId.RECGOV,
                            error = "cart_not_added",
                            detail = "cart automation did not confirm a cart hold",
                            request = buildJsonObject { put("watch_id", request.watchId) },
                            response =
                                buildJsonObject {
                                    put("ok", false)
                                    put("error", "cart_not_added")
                                },
                        )
                    },
                )
            val bookingProviders = listOf<BookingProvider>(bookingProvider)
            val notifications = CapturingNotifications(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = bookingProviders,
                    bookingTargets = AvailabilityBookingTargetResolver(bookingProviders),
                    notifications = notifications,
                )

            val delivered =
                handler.fire(
                    fakeWatch(id = 42L, triggerKinds = listOf(AtcTriggerActionHandler.KIND)),
                    openings = listOf(triggerOpening()),
                )

            assertFalse(delivered)
            assertEquals("failed", notifications.atcResults.single().status)
        }

    @Test
    fun `AtcTriggerActionHandler slack-notifies recgov health preflight failure`() =
        runBlocking {
            val bookingProvider =
                RecordingBookingProvider(
                    resultFactory = { request: AddToCartRequest ->
                        AddToCartResult.Failed(
                            providerId = BookingProviderId.RECGOV,
                            error = "recgov_not_authenticated",
                            detail = "run make recgov-login",
                            request = buildJsonObject { put("watch_id", request.watchId) },
                            response =
                                buildJsonObject {
                                    put("ok", true)
                                    putJsonObject("recgov_auth") {
                                        put("login_status", "failed")
                                        put("error", "recgov_not_authenticated")
                                        put("detail", "run make recgov-login")
                                    }
                                },
                        )
                    },
                )
            val bookingProviders = listOf<BookingProvider>(bookingProvider)
            val notifications = CapturingNotifications(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = bookingProviders,
                    bookingTargets = AvailabilityBookingTargetResolver(bookingProviders),
                    notifications = notifications,
                )

            val delivered =
                handler.fire(
                    fakeWatch(id = 42L, triggerKinds = listOf(AtcTriggerActionHandler.KIND)),
                    openings = listOf(triggerOpening()),
                )

            assertFalse(delivered)
            val result = notifications.atcResults.single()
            assertEquals("failed", result.status)
            val auth = result.response!!["recgov_auth"]!!.jsonObject
            assertEquals("recgov_not_authenticated", auth["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `AtcTriggerActionHandler leaves unsupported openings inert`() =
        runBlocking {
            val bookingProvider = RecordingBookingProvider()
            val bookingProviders = listOf<BookingProvider>(bookingProvider)
            val notifications = CapturingNotifications(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = bookingProviders,
                    bookingTargets = AvailabilityBookingTargetResolver(bookingProviders),
                    notifications = notifications,
                )

            val delivered =
                handler.fire(
                    fakeWatch(id = 42L, triggerKinds = listOf(AtcTriggerActionHandler.KIND)),
                    openings = listOf(triggerOpening(parentRef = ProviderRef.Campflare("campflare-1"))),
                )

            assertFalse(delivered)
            assertTrue(bookingProvider.requests.isEmpty())
            assertTrue(notifications.atcResults.isEmpty())
        }

    private class FakeHandler(
        kind: String,
        supportedKinds: Set<String> = setOf(kind),
        private val result: Boolean = true,
    ) : TriggerActionHandler {
        override val kinds: Set<String> = supportedKinds
        var calls: Int = 0

        override suspend fun fire(
            watch: AvailabilityWatchRepo.Watch,
            openings: List<TriggerOpening>,
        ): Boolean {
            calls++
            return result
        }
    }

    /** [NotificationSender] double that records aggregate notification calls. */
    private class CapturingNotifications(
        private val result: Boolean,
    ) : NotificationSender {
        data class AtcResult(
            val watchId: Long,
            val vendor: String,
            val status: String,
            val response: JsonObject?,
            val targets: List<NotificationTarget>,
        )

        var lastWatchId: Long? = null
        var lastTargets: List<NotificationTarget> = emptyList()
        var lastAppRootUrl: String? = null
        var openingSends: Int = 0
        val atcResults = mutableListOf<AtcResult>()

        override suspend fun sendWatchOpenings(
            watchId: Long,
            startDate: LocalDate,
            endDate: LocalDate,
            openings: List<WatchOpening>,
            targets: List<NotificationTarget>,
            appRootUrl: String?,
        ): Boolean {
            lastWatchId = watchId
            lastTargets = targets
            lastAppRootUrl = appRootUrl
            openingSends++
            return result
        }

        override suspend fun sendWatchStatus(
            notice: WatchStatusNotice,
            targets: List<NotificationTarget>,
        ): Boolean = result

        override suspend fun sendAtcResult(
            watchId: Long,
            vendor: String,
            status: String,
            request: JsonObject,
            response: JsonObject?,
            targets: List<NotificationTarget>,
        ): Boolean {
            atcResults += AtcResult(watchId, vendor, status, response, targets)
            return result
        }
    }

    private fun fakeWatch(
        id: Long,
        triggerConfig: JsonObject = JsonObject(emptyMap()),
        triggerKinds: List<String> = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY),
        stopWhenTriggered: Boolean = false,
    ): AvailabilityWatchRepo.Watch =
        AvailabilityWatchRepo.Watch(
            id = id,
            targets = emptyList<AvailabilityWatchTargetRepo.WatchTarget>(),
            campsiteFilters = JsonObject(emptyMap()),
            startDate = LocalDate.parse("2026-07-04"),
            endDate = LocalDate.parse("2026-07-06"),
            cadenceSec = null,
            triggerKinds = triggerKinds,
            triggerConfig = triggerConfig,
            stopWhenTriggered = stopWhenTriggered,
            status = WatchStatus.ACTIVE,
            createdAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-07-01T00:00:00Z"),
        )

    private class RecordingBookingProvider(
        private val resultFactory: ((AddToCartRequest) -> AddToCartResult)? = null,
    ) : BookingProvider {
        val requests = mutableListOf<AddToCartRequest>()

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
                target.parentRef is ProviderRef.RecGov

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
            requests += request
            resultFactory?.let { return it(request) }
            return AddToCartResult.Completed(
                providerId = BookingProviderId.RECGOV,
                request = buildJsonObject { put("watch_id", request.watchId) },
                response =
                    buildJsonObject {
                        put("ok", true)
                        put("cart_added", true)
                    },
            )
        }
    }

    private class FakeAvailabilityProvider(
        override val id: AvailabilityProviderId,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            ref: ProviderRef,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private fun triggerOpening(parentRef: ProviderRef = ProviderRef.RecGov("100")): TriggerOpening {
        val providerId =
            when (parentRef) {
                is ProviderRef.RecGov -> AvailabilityProviderId.RECGOV
                is ProviderRef.Campflare -> AvailabilityProviderId.CAMPFLARE
                is ProviderRef.Aspira -> AvailabilityProviderId.ASPIRA
                is ProviderRef.ReserveAmerica -> AvailabilityProviderId.RESERVEAMERICA
                is ProviderRef.ReserveCalifornia -> AvailabilityProviderId.RESERVECALIFORNIA
            }
        val campsite =
            CampsiteAvailabilityTarget(
                id = 7L,
                vendor = "recgov",
                vendorId = "site-7",
                name = "Site 12",
                loop = "Loop A",
                siteType = "Tent",
                raw = null,
            )
        val catalogRef = CatalogCampsiteRef(campsiteId = 7L, vendorId = "site-7")
        return TriggerOpening(
            campsite = campsite,
            date = LocalDate.parse("2026-07-04"),
            resolvedTarget =
                ResolvedAvailabilityTarget(
                    campsite = campsite,
                    provider = FakeAvailabilityProvider(providerId),
                    parentRef = parentRef,
                    catalogRef = catalogRef,
                    parentPoiId = 100L,
                    dateContext = PoiDateContext(ZoneId.of("UTC"), LocalDate.parse("2026-07-01")),
                ),
            notification =
                WatchOpening(
                    label = "Site 12",
                    loop = "Loop A",
                    siteType = "Tent",
                    date = LocalDate.parse("2026-07-04"),
                    campgroundId = 100L,
                    campground = "Test CG",
                    bookingUrl = "https://example.test/book",
                    vendor = "recgov",
                ),
        )
    }
}
