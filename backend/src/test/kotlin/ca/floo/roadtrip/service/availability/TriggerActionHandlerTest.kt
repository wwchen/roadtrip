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
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.notification.SlackNotificationService
import ca.floo.roadtrip.service.notification.WatchOpening
import ca.floo.roadtrip.service.notification.WatchStatusNotice
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TriggerActionHandlerTest {
    @Test
    fun `known kind fires its handler`() =
        runBlocking {
            val fake = FakeHandler(kind = "slack_notify", result = true)
            val registry = TriggerActionRegistry(listOf(fake))

            val handler = registry.forKind("slack_notify")
            assertNotNull(handler)
            assertTrue(handler.fire(fakeWatch(id = 1L), openings = emptyList()))
            assertEquals(1, fake.calls)
        }

    @Test
    fun `unknown kind returns null and is inert`() {
        val registry = TriggerActionRegistry(listOf(FakeHandler(kind = "slack_notify")))
        assertNull(registry.forKind("email"))
    }

    @Test
    fun `duplicate kinds in constructor throws`() {
        val error =
            assertFailsWith<IllegalArgumentException> {
                TriggerActionRegistry(listOf(FakeHandler(kind = "dup"), FakeHandler(kind = "dup")))
            }
        assertTrue(error.message!!.contains("duplicate handler kinds"))
    }

    @Test
    fun `SlackNotifyHandler forwards channel override to slack service`() =
        runBlocking {
            val slack = CapturingSlack(result = true)
            val handler = SlackNotifyHandler(slack = slack, appRootUrl = "https://app.example")

            val watch =
                fakeWatch(
                    id = 42L,
                    triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("custom-channel"))),
                )
            val delivered = handler.fire(watch, openings = listOf(triggerOpening()))

            assertTrue(delivered)
            assertEquals(42L, slack.lastWatchId)
            assertEquals("custom-channel", slack.lastChannel)
            assertEquals("https://app.example", slack.lastAppRootUrl)
        }

    @Test
    fun `SlackNotifyHandler omits channel when triggerConfig has none`() =
        runBlocking {
            val slack = CapturingSlack(result = true)
            val handler = SlackNotifyHandler(slack = slack, appRootUrl = null)

            handler.fire(fakeWatch(id = 7L), openings = listOf(triggerOpening()))

            // Null channel makes the service fall back to its configured default.
            assertNull(slack.lastChannel)
        }

    @Test
    fun `SlackNotifyHandler reports transport failure as false`() =
        runBlocking {
            // The dispatcher's "watch goes DONE only when fire() returns true"
            // gate is asserted at the dispatcher layer (AvailabilityPollExecutorTest
            // covers stopWhenTriggered against a failing Slack service); here we
            // verify the handler itself forwards the transport's success flag.
            val slack = CapturingSlack(result = false)
            val handler = SlackNotifyHandler(slack = slack, appRootUrl = null)

            assertFalse(handler.fire(fakeWatch(id = 9L), openings = listOf(triggerOpening())))
        }

    @Test
    fun `AtcTriggerActionHandler queues first supported opening through booking provider`() =
        runBlocking {
            val bookingProvider = RecordingBookingProvider(notifiedWaiters = 1)
            val registry = BookingProviderRegistry(listOf(bookingProvider))
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    slack = CapturingSlack(result = true),
                )
            val watch = fakeWatch(id = 42L, triggerKinds = listOf(AtcTriggerActionHandler.KIND), stopWhenTriggered = true)

            val delivered = handler.fire(watch, openings = listOf(triggerOpening()))

            assertFalse(delivered)
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
    fun `AtcTriggerActionHandler sends offline slack when no companion waiter is connected`() =
        runBlocking {
            val bookingProvider = RecordingBookingProvider(notifiedWaiters = 0)
            val registry = BookingProviderRegistry(listOf(bookingProvider))
            val slack = CapturingSlack(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    slack = slack,
                )
            val watch =
                fakeWatch(
                    id = 42L,
                    triggerKinds = listOf(AtcTriggerActionHandler.KIND),
                    triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("#custom"))),
                )

            handler.fire(watch, openings = listOf(triggerOpening()))

            val alert = slack.offlineAlerts.single()
            assertEquals(42L, alert.watchId)
            assertEquals("recgov", alert.vendor)
            assertEquals("#custom", alert.channel)
            assertEquals("Site 12", alert.openings.single().label)
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
            val registry = BookingProviderRegistry(listOf(bookingProvider))
            val slack = CapturingSlack(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    slack = slack,
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
            val result = slack.atcResults.single()
            assertEquals(42L, result.watchId)
            assertEquals("recgov", result.vendor)
            assertEquals("completed", result.status)
            assertEquals("#custom", result.channel)
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
            val registry = BookingProviderRegistry(listOf(bookingProvider))
            val slack = CapturingSlack(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    slack = slack,
                )

            val delivered =
                handler.fire(
                    fakeWatch(id = 42L, triggerKinds = listOf(AtcTriggerActionHandler.KIND)),
                    openings = listOf(triggerOpening()),
                )

            assertFalse(delivered)
            assertEquals("failed", slack.atcResults.single().status)
        }

    @Test
    fun `AtcTriggerActionHandler leaves unsupported openings inert`() =
        runBlocking {
            val bookingProvider = RecordingBookingProvider(notifiedWaiters = 1)
            val registry = BookingProviderRegistry(listOf(bookingProvider))
            val slack = CapturingSlack(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    slack = slack,
                )

            val delivered =
                handler.fire(
                    fakeWatch(id = 42L, triggerKinds = listOf(AtcTriggerActionHandler.KIND)),
                    openings = listOf(triggerOpening(parentRef = ProviderRef.Campflare("campflare-1"))),
                )

            assertFalse(delivered)
            assertTrue(bookingProvider.requests.isEmpty())
            assertTrue(slack.offlineAlerts.isEmpty())
        }

    private class FakeHandler(
        override val kind: String,
        private val result: Boolean = true,
    ) : TriggerActionHandler {
        var calls: Int = 0

        override suspend fun fire(
            watch: AvailabilityWatchRepo.Watch,
            openings: List<TriggerOpening>,
        ): Boolean {
            calls++
            return result
        }
    }

    /** [SlackNotificationService] double that records the last call to
     *  [sendWatchOpenings]; other methods no-op because the handler under test
     *  only exercises that one seam. */
    private class CapturingSlack(
        private val result: Boolean,
    ) : SlackNotificationService {
        data class OfflineAlert(
            val watchId: Long,
            val vendor: String,
            val openings: List<WatchOpening>,
            val channel: String?,
        )

        data class AtcResult(
            val watchId: Long,
            val vendor: String,
            val status: String,
            val channel: String?,
        )

        var lastWatchId: Long? = null
        var lastChannel: String? = null
        var lastAppRootUrl: String? = null
        val offlineAlerts = mutableListOf<OfflineAlert>()
        val atcResults = mutableListOf<AtcResult>()

        override suspend fun sendWatchOpenings(
            watchId: Long,
            startDate: LocalDate,
            endDate: LocalDate,
            openings: List<WatchOpening>,
            channel: String?,
            appRootUrl: String?,
        ): Boolean {
            lastWatchId = watchId
            lastChannel = channel
            lastAppRootUrl = appRootUrl
            return result
        }

        override suspend fun sendWatchStatus(
            notice: WatchStatusNotice,
            channel: String?,
        ): Boolean = result

        override suspend fun sendAtcCompanionOffline(
            watchId: Long,
            vendor: String,
            openings: List<WatchOpening>,
            channel: String?,
        ): Boolean {
            offlineAlerts += OfflineAlert(watchId, vendor, openings, channel)
            return result
        }

        override suspend fun sendAtcResult(
            watchId: Long,
            vendor: String,
            status: String,
            request: JsonObject,
            response: JsonObject?,
            channel: String?,
        ): Boolean {
            atcResults += AtcResult(watchId, vendor, status, channel)
            return result
        }

        override suspend fun postResponseWatchStatus(
            responseUrl: String,
            notice: WatchStatusNotice,
        ): Boolean = result

        override suspend fun postResponseStaleWatch(
            responseUrl: String,
            watchId: Long,
        ): Boolean = result
    }

    private fun fakeWatch(
        id: Long,
        triggerConfig: JsonObject = JsonObject(emptyMap()),
        triggerKinds: List<String> = listOf(SlackNotifyHandler.KIND),
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
        private val notifiedWaiters: Int = 1,
    ) : BookingProvider {
        constructor(notifiedWaiters: Int) : this(resultFactory = null, notifiedWaiters = notifiedWaiters)

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
            return AddToCartResult.Queued(dispatchId = 99L, providerId = BookingProviderId.RECGOV, notifiedWaiters = notifiedWaiters)
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
