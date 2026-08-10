package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.fixtures.campsiteFixture
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.PoiDateContext
import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.auth.User
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.auth.UserStatus
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.AvailabilityWatchTargetRepo
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.testCampground
import ca.floo.roadtrip.service.booking.BookingAdapter
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import ca.floo.roadtrip.service.notification.common.NotificationSender
import ca.floo.roadtrip.service.notification.common.NotificationTarget
import ca.floo.roadtrip.service.notification.common.WatchOpening
import ca.floo.roadtrip.service.notification.common.WatchStatusNotice
import ca.floo.roadtrip.service.security.SecretCipher
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TriggerActionHandlerTest {
    private val testCipher = SecretCipher(ByteArray(32) { it.toByte() })

    @Test
    fun `known kind fires its handler`() =
        runBlocking {
            val fake = FakeHandler(kind = "slack_notify", result = true)
            val registry = TriggerActionRegistry(listOf(fake))

            val handler = registry.forKinds(listOf("slack_notify")).singleOrNull()
            assertNotNull(handler)
            assertTrue(handler.fire(fakeWatch(id = 1L), openings = emptyList()))
            assertEquals(1, fake.calls)
        }

    @Test
    fun `unknown kind returns empty handler list and is inert`() {
        val registry = TriggerActionRegistry(listOf(FakeHandler(kind = "slack_notify")))
        assertTrue(registry.forKinds(listOf("email")).isEmpty())
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
    fun `registry returns an aggregate handler once for multiple matching kinds`() {
        val fake =
            FakeHandler(
                kind = AvailabilityTriggerKinds.SLACK_NOTIFY,
                supportedKinds = setOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.EMAIL_NOTIFY),
            )
        val registry = TriggerActionRegistry(listOf(fake))

        assertEquals(listOf(fake), registry.forKinds(listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.EMAIL_NOTIFY)))
    }

    @Test
    fun `NotifyTriggerActionHandler forwards slack channel override as notification target`() =
        runBlocking {
            val notifications = CapturingNotifications(result = true)
            val handler =
                NotifyTriggerActionHandler(
                    notifications = notifications,
                    targetResolver =
                        resolver(
                            UserSettingsRepo.Settings(
                                notificationEmail = null,
                                slackChannel = null,
                                slackTokenCipher = testCipher.seal("xoxb-owner-token"),
                                slackTokenHint = "oken",
                            ),
                        ),
                    appRootUrl = "https://app.example",
                )

            val watch =
                fakeWatch(
                    id = 42L,
                    triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY),
                    triggerConfig = JsonObject(mapOf("channel" to JsonPrimitive("custom-channel"))),
                )
            val delivered = handler.fire(watch, openings = listOf(triggerOpening()))

            assertTrue(delivered)
            assertEquals(42L, notifications.lastWatchId)
            assertEquals(listOf(NotificationTarget.Slack("custom-channel", "xoxb-owner-token")), notifications.lastTargets)
            assertEquals("https://app.example", notifications.lastAppRootUrl)
        }

    @Test
    fun `NotifyTriggerActionHandler prefers nested channel override over legacy flat channel`() =
        runBlocking {
            val notifications = CapturingNotifications(result = true)
            val handler =
                NotifyTriggerActionHandler(
                    notifications = notifications,
                    targetResolver =
                        resolver(
                            UserSettingsRepo.Settings(
                                notificationEmail = null,
                                slackChannel = null,
                                slackTokenCipher = testCipher.seal("xoxb-owner-token"),
                                slackTokenHint = "oken",
                            ),
                        ),
                    appRootUrl = "https://app.example",
                )

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
            assertEquals(listOf(NotificationTarget.Slack("nested-channel", "xoxb-owner-token")), notifications.lastTargets)
        }

    @Test
    fun `NotifyTriggerActionHandler emits no slack target when owner has no channel`() =
        runBlocking {
            // Leak-closure: no watch override AND no owner-controlled channel means
            // NO Slack target — the card must not fall through to a shared default.
            // With nothing to send, the handler reports no delivery.
            val notifications = CapturingNotifications(result = true)
            val handler =
                NotifyTriggerActionHandler(notifications = notifications, targetResolver = resolver(), appRootUrl = "https://app.example")

            val delivered =
                handler.fire(
                    fakeWatch(
                        id = 42L,
                        triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY),
                    ),
                    openings = listOf(triggerOpening()),
                )

            assertFalse(delivered)
            assertTrue(notifications.lastTargets.isEmpty())
        }

    @Test
    fun `NotifyTriggerActionHandler falls back to the owner's stored channel and token`() =
        runBlocking {
            // No watch override, but the owner has a stored channel + sealed token:
            // the card lands in the owner's channel via their per-user token.
            val notifications = CapturingNotifications(result = true)
            val handler =
                NotifyTriggerActionHandler(
                    notifications = notifications,
                    targetResolver =
                        WatchNotificationTargetResolver(
                            userSettingsRepo =
                                FakeUserSettingsRepo(
                                    UserSettingsRepo.Settings(
                                        notificationEmail = null,
                                        slackChannel = "#owner",
                                        slackTokenCipher = testCipher.seal("xoxb-owner"),
                                        slackTokenHint = "ner",
                                    ),
                                ),
                            userRepo = FakeUserRepo(),
                            cipher = testCipher,
                        ),
                    appRootUrl = "https://app.example",
                )

            val delivered =
                handler.fire(
                    fakeWatch(id = 42L, triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY)),
                    openings = listOf(triggerOpening()),
                )

            assertTrue(delivered)
            assertEquals(listOf(NotificationTarget.Slack(channel = "#owner", token = "xoxb-owner")), notifications.lastTargets)
        }

    @Test
    fun `NotifyTriggerActionHandler sends email target`() =
        runBlocking {
            val notifications = CapturingNotifications(result = true)
            val handler =
                NotifyTriggerActionHandler(notifications = notifications, targetResolver = resolver(), appRootUrl = "https://app.example")

            val delivered =
                handler.fire(
                    fakeWatch(
                        id = 42L,
                        triggerKinds = listOf(AvailabilityTriggerKinds.EMAIL_NOTIFY),
                        triggerConfig =
                            JsonObject(
                                mapOf(
                                    AvailabilityTriggerKinds.EMAIL_NOTIFY to
                                        JsonObject(
                                            mapOf("to" to JsonPrimitive("alerts@example.test")),
                                        ),
                                ),
                            ),
                    ),
                    openings = listOf(triggerOpening()),
                )

            assertTrue(delivered)
            assertEquals(listOf(NotificationTarget.Email(listOf("owner@example.test"))), notifications.lastTargets)
        }

    @Test
    fun `NotifyTriggerActionHandler combines slack and email targets into one send`() =
        runBlocking {
            val notifications = CapturingNotifications(result = true)
            val handler =
                NotifyTriggerActionHandler(
                    notifications = notifications,
                    targetResolver =
                        resolver(
                            UserSettingsRepo.Settings(
                                notificationEmail = null,
                                slackChannel = null,
                                slackTokenCipher = testCipher.seal("xoxb-owner-token"),
                                slackTokenHint = "oken",
                            ),
                        ),
                    appRootUrl = null,
                )

            val delivered =
                handler.fire(
                    fakeWatch(
                        id = 42L,
                        triggerKinds = listOf(AvailabilityTriggerKinds.SLACK_NOTIFY, AvailabilityTriggerKinds.EMAIL_NOTIFY),
                        triggerConfig =
                            JsonObject(
                                mapOf(
                                    "channel" to JsonPrimitive("#custom"),
                                    AvailabilityTriggerKinds.EMAIL_NOTIFY to
                                        JsonObject(
                                            mapOf("to" to JsonPrimitive("alerts@example.test")),
                                        ),
                                ),
                            ),
                    ),
                    openings = listOf(triggerOpening()),
                )

            assertTrue(delivered)
            assertEquals(
                listOf(NotificationTarget.Slack("#custom", "xoxb-owner-token"), NotificationTarget.Email(listOf("owner@example.test"))),
                notifications.lastTargets,
            )
            assertEquals(1, notifications.openingSends)
        }

    @Test
    fun `NotifyTriggerActionHandler reports aggregate transport failure as false`() =
        runBlocking {
            val handler =
                NotifyTriggerActionHandler(
                    notifications = CapturingNotifications(result = false),
                    targetResolver = resolver(),
                    appRootUrl = null,
                )

            assertFalse(handler.fire(fakeWatch(id = 9L), openings = listOf(triggerOpening())))
        }

    @Test
    fun `AtcTriggerActionHandler executes first supported opening through booking provider`() =
        runBlocking {
            val bookingProvider = RecordingBookingProvider()
            val registry = BookingAdapterRegistry(listOf(bookingProvider))
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    targetResolver = resolver(),
                    notifications = CapturingNotifications(result = true),
                )
            val watch = fakeWatch(id = 42L, triggerKinds = listOf(AtcTriggerActionHandler.KIND), stopWhenTriggered = true)

            val delivered = handler.fire(watch, openings = listOf(triggerOpening()))

            assertTrue(delivered)
            val request = bookingProvider.requests.single()
            assertEquals(42L, request.watchId)
            assertEquals(BookingProvider.RECGOV, request.target.providerId)
            assertEquals(7L, request.target.campsiteId)
            assertEquals("site-7", request.target.vendorSiteId)
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
                            providerId = BookingProvider.RECGOV,
                            request = buildJsonObject { put("watch_id", request.watchId) },
                            response =
                                buildJsonObject {
                                    put("ok", true)
                                    put("cart_added", true)
                                },
                        )
                    },
                )
            val registry = BookingAdapterRegistry(listOf(bookingProvider))
            val notifications = CapturingNotifications(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    targetResolver =
                        resolver(
                            UserSettingsRepo.Settings(
                                notificationEmail = null,
                                slackChannel = null,
                                slackTokenCipher = testCipher.seal("xoxb-owner-token"),
                                slackTokenHint = "oken",
                            ),
                        ),
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
            assertEquals(listOf(NotificationTarget.Slack("#custom", "xoxb-owner-token")), result.targets)
        }

    @Test
    fun `AtcTriggerActionHandler reports direct companion failure without marking fired`() =
        runBlocking {
            val bookingProvider =
                RecordingBookingProvider(
                    resultFactory = { request: AddToCartRequest ->
                        AddToCartResult.Failed(
                            providerId = BookingProvider.RECGOV,
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
            val registry = BookingAdapterRegistry(listOf(bookingProvider))
            val notifications = CapturingNotifications(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    targetResolver = resolver(),
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
                            providerId = BookingProvider.RECGOV,
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
            val registry = BookingAdapterRegistry(listOf(bookingProvider))
            val notifications = CapturingNotifications(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    targetResolver = resolver(),
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
            val registry = BookingAdapterRegistry(listOf(bookingProvider))
            val notifications = CapturingNotifications(result = true)
            val handler =
                AtcTriggerActionHandler(
                    bookings = registry,
                    bookingTargets = AvailabilityBookingTargetResolver(registry),
                    targetResolver = resolver(),
                    notifications = notifications,
                )

            val delivered =
                handler.fire(
                    fakeWatch(id = 42L, triggerKinds = listOf(AtcTriggerActionHandler.KIND)),
                    openings = listOf(triggerOpening(parentRef = BookingProviderRef.Campflare("campflare-1"))),
                )

            assertFalse(delivered)
            assertTrue(bookingProvider.requests.isEmpty())
            assertTrue(notifications.atcResults.isEmpty())
        }

    /** In-memory [UserSettingsRepo] returning a fixed [Settings] for owner id 1
     *  (the [fakeWatch] owner), so the resolver's owner-channel lookup is
     *  exercised without a database. */
    private class FakeUserSettingsRepo(
        private val settings: UserSettingsRepo.Settings?,
    ) : UserSettingsRepo(ctx = DSL.using(SQLDialect.POSTGRES)) {
        override fun find(userId: UserId): UserSettingsRepo.Settings? = settings
    }

    private class FakeUserRepo : UserRepo(ctx = DSL.using(SQLDialect.POSTGRES)) {
        override fun findById(id: UserId): User =
            User(
                id = id,
                email = "owner@example.test",
                displayName = null,
                isEmailVerified = true,
                status = UserStatus.ACTIVE,
                roles = emptySet(),
                createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            )
    }

    /** A resolver over an owner that has no stored settings by default. When
     *  settings with a token are provided, testCipher is passed so the token can
     *  be decrypted, otherwise cipher is null. */
    private fun resolver(settings: UserSettingsRepo.Settings? = null): WatchNotificationTargetResolver =
        WatchNotificationTargetResolver(
            userSettingsRepo = FakeUserSettingsRepo(settings),
            userRepo = FakeUserRepo(),
            cipher = if (settings?.slackTokenCipher != null) testCipher else null,
        )

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
            ownerUserId = 1L,
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
    ) : BookingAdapter {
        val requests = mutableListOf<AddToCartRequest>()

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
                target.parentRef is BookingProviderRef.RecGov

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
            requests += request
            resultFactory?.let { return it(request) }
            return AddToCartResult.Completed(
                providerId = BookingProvider.RECGOV,
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
        override val id: BookingProvider,
    ) : AvailabilityProvider {
        override val capabilities: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = true,
                bookingHorizonDays = 365,
                maxPollWindowDays = 60,
            )

        override fun isEnabled(): Boolean = true

        override suspend fun availability(
            campground: Campground,
            startDate: LocalDate,
            endDate: LocalDate,
        ): AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
    }

    private fun triggerOpening(parentRef: BookingProviderRef = BookingProviderRef.RecGov(facilityId = "100")): TriggerOpening {
        val providerId =
            when (parentRef) {
                is BookingProviderRef.RecGov -> BookingProvider.RECGOV
                is BookingProviderRef.Campflare -> BookingProvider.CAMPFLARE
                is BookingProviderRef.Aspira -> BookingProvider.ASPIRA
                is BookingProviderRef.ReserveAmerica -> BookingProvider.RESERVEAMERICA
                is BookingProviderRef.ReserveCalifornia -> BookingProvider.RESERVECALIFORNIA
            }
        val campground = campgroundForRef(parentRef)
        val campsite =
            campsiteFixture(
                id = 7L,
                vendor = "recgov",
                vendorId = "site-7",
                name = "Site 12",
                loopName = "Loop A",
                kind = "Tent",
                sourcePayload = null,
            )
        return TriggerOpening(
            campsite = campsite,
            date = LocalDate.parse("2026-07-04"),
            resolvedTarget =
                ResolvedAvailabilityTarget(
                    campsite = campsite,
                    provider = FakeAvailabilityProvider(providerId),
                    campground = campground,
                    parentPoiId = 100L,
                    dateContext = PoiDateContext(ZoneId.of("UTC"), LocalDate.parse("2026-07-01")),
                ),
            watchOpening =
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

    private fun campgroundForRef(ref: BookingProviderRef): Campground {
        val refStr =
            when (ref) {
                is BookingProviderRef.RecGov -> ref.facilityId
                is BookingProviderRef.Campflare -> ref.campgroundId
                is BookingProviderRef.Aspira ->
                    "${ref.tenant}:${ref.transactionLocationId}:${ref.mapId}:${ref.resourceLocationId}"
                is BookingProviderRef.ReserveAmerica -> "${ref.contractCode}:${ref.parkId}"
                is BookingProviderRef.ReserveCalifornia ->
                    "${ref.placeId}:${ref.facilityIds.joinToString(",")}"
            }
        return testCampground(
            bookingProvider = ref.provider.id,
            bookingProviderRef = refStr,
        )
    }
}
