package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.api.MAGIC_LINK_TOKEN_PARAM
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.route.auth.SESSION_COOKIE
import ca.floo.roadtrip.route.auth.roadtripAuthorization
import ca.floo.roadtrip.service.auth.MagicLinkTokenService
import ca.floo.roadtrip.service.auth.WatchAccessResolver
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.AvailabilityWatchApiMapper
import ca.floo.roadtrip.service.availability.AvailabilityWatchController
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.WatchCapabilityValidator
import ca.floo.roadtrip.service.availability.WatchLifecycleNotifications
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.WatchTriggerCapabilityValidator
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import ca.floo.roadtrip.service.availability.alert.InternalPollerAlertProvider
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import ca.floo.roadtrip.route.api.availability.availabilityWatchRoutes as installAvailabilityWatchRoutes

private const val WATCHES_PATH = "/api/watches"
private const val MODIFY_ACTION = "modify"
private const val DELETE_ACTION = "delete"

private fun watchPath(id: Long): String = "$WATCHES_PATH/$id"

private fun modifyWatchPath(id: Long): String = "${watchPath(id)}/$MODIFY_ACTION"

private fun deleteWatchPath(id: Long): String = "${watchPath(id)}/$DELETE_ACTION"

private const val USER_TOKEN = "user-token"
private const val OTHER_TOKEN = "other-token"
private const val ADMIN_TOKEN = "admin-token"

class AvailabilityWatchRoutesTest : SharedDbTest() {
    private var ownerId: UserId? = null
    private var otherId: UserId? = null
    private var adminId: UserId? = null

    private fun seedUsers() {
        if (ownerId == null) {
            val userRepo = UserRepo(ctx)
            ownerId = userRepo.create("owner@example.com", null, true).id
            otherId = userRepo.create("other@example.com", null, true).id
            adminId = userRepo.create("admin@example.com", null, true).id
            userRepo.grantRole(adminId!!, ca.floo.roadtrip.model.domain.auth.Role.ADMIN)
        }
    }

    private fun resolvePrincipalFor(token: String?): Principal =
        when (token) {
            USER_TOKEN -> Principal.User(ownerId!!, roles = emptySet())
            OTHER_TOKEN -> Principal.User(otherId!!, roles = emptySet())
            ADMIN_TOKEN -> Principal.User(adminId!!, roles = setOf(ca.floo.roadtrip.model.domain.auth.Role.ADMIN))
            else -> Principal.Anonymous
        }

    private fun HttpRequestBuilder.asUser(token: String = USER_TOKEN) {
        header(HttpHeaders.Cookie, "$SESSION_COOKIE=$token")
    }

    private fun createBody(poiId: Long): String =
        """{"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-06", "cadence_sec": 60, "trigger_kinds": ["atc"]}"""

    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
        // Clean users between tests
        ctx.execute("DELETE FROM app_user")
        // Reset user state for seedUsers()
        ownerId = null
        otherId = null
    }

    /**
     * Builds the watch service the routes take. The target resolver is real
     * but backed by an empty provider registry, so POIs without a resolvable
     * availability provider produce no poller links — which is fine for the
     * CRUD assertions here (poller membership is exercised in the membership
     * and executor tests).
     */
    private fun watchService(): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = CampgroundRepo(ctx),
                availabilityProviders = emptyList(),
                dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        return AvailabilityWatchService(
            ctx = ctx,
            alertProviders = alertProviders(campsitesRepo, targets),
            capabilityValidator = WatchCapabilityValidator { },
            lifecycleNotifications = ignoredLifecycleNotifications(),
        )
    }

    /**
     * Watch service whose registry maps the test POI source ('test') to a
     * recgov adapter, so a POI with a `{"recgov_id": ...}` provider_ref
     * resolves to a real (recgov, parentRef) poller. Used to exercise poller
     * membership on watch mutation.
     */
    private fun watchServiceWithRecgov(): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = CampgroundRepo(ctx),
                availabilityProviders = listOf(FakeRecgovProvider),
                dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        return AvailabilityWatchService(
            ctx = ctx,
            alertProviders = alertProviders(campsitesRepo, targets),
            capabilityValidator = WatchCapabilityValidator { },
            lifecycleNotifications = ignoredLifecycleNotifications(),
        )
    }

    private fun watchCapabilitiesWithRecgov(): WatchCapabilityService {
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = CampgroundRepo(ctx),
                availabilityProviders = listOf(FakeRecgovProvider),
                dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        return WatchCapabilityService(
            availabilityTargets = targets,
            bookingTargets = AvailabilityBookingTargetResolver(BookingAdapterRegistry(emptyList())),
        )
    }

    private fun watchServiceRejectingAtc(): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                poiRepo = PoiRepo(ctx),
                campsitesRepo = campsitesRepo,
                campgroundRepo = CampgroundRepo(ctx),
                availabilityProviders = emptyList(),
                dateResolver = AvailabilityDateResolver(PoiRepo(ctx)),
                pollerRepo = AvailabilityPollerRepo(ctx),
            )
        val scopeResolver = WatchScopeResolver(campsitesRepo)
        return AvailabilityWatchService(
            ctx = ctx,
            alertProviders = alertProviders(campsitesRepo, targets),
            capabilityValidator =
                WatchTriggerCapabilityValidator(
                    scopeResolver = scopeResolver,
                    watchCapabilityService =
                        WatchCapabilityService(
                            availabilityTargets = targets,
                            bookingTargets = AvailabilityBookingTargetResolver(BookingAdapterRegistry(emptyList())),
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

    /** Wraps the default internal-poller alert provider for tests, mirroring
     *  the production wiring in [ca.floo.roadtrip.di.serviceModule]. */
    private fun alertProviders(
        campsitesRepo: CampsiteRepo,
        targets: DbAvailabilityTargetResolver,
    ): AlertProviderRegistry =
        AlertProviderRegistry(
            listOf(
                InternalPollerAlertProvider(
                    AvailabilityPollerMembership(WatchScopeResolver(campsitesRepo), targets),
                ),
            ),
        )

    private fun Route.availabilityWatchRoutes(
        ctx: DSLContext,
        watchService: AvailabilityWatchService,
        watchCapabilities: WatchCapabilityService? = null,
    ) {
        installAvailabilityWatchRoutes(availabilityWatchController(ctx, watchService, watchCapabilities))
    }

    private fun availabilityWatchController(
        ctx: DSLContext,
        watchService: AvailabilityWatchService,
        watchCapabilities: WatchCapabilityService? = null,
    ): AvailabilityWatchController {
        val campsitesRepo = CampsiteRepo(ctx)
        val watchRepo = AvailabilityWatchRepo(ctx)
        val userRepo = UserRepo(ctx)
        return AvailabilityWatchController(
            watchRepo = watchRepo,
            watchService = watchService,
            watchMapper =
                AvailabilityWatchApiMapper(
                    campsiteRepo = campsitesRepo,
                    scopeResolver = WatchScopeResolver(campsitesRepo),
                    watchCapabilityService = watchCapabilities,
                ),
            accessResolver = WatchAccessResolver(watchRepo = watchRepo, userRepo = userRepo),
        )
    }

    /** The real service over the test DB, so these exercise real minting. */
    private fun magicLinkTokens(ctx: DSLContext): MagicLinkTokenService = MagicLinkTokenService(watchRepo = AvailabilityWatchRepo(ctx))

    @Test
    fun `POST creates a poi-scoped watch with filters`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p1", name = "Upper Pines")
            val body =
                """
                {
                  "poi_id": $poiId,
                  "campsite_filters": {"loop": ["A"]},
                  "start_date": "2026-07-04",
                  "end_date": "2026-07-06",
                  "cadence_sec": 60,
                  "trigger_kinds": ["atc"]
                }
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals(poiId, obj["poi_id"]!!.jsonPrimitive.long)
            assertEquals("2026-07-04", obj["start_date"]!!.jsonPrimitive.content)
            assertEquals("2026-07-06", obj["end_date"]!!.jsonPrimitive.content)
            assertEquals(false, obj.containsKey("target_dates"))
            assertEquals(false, obj.containsKey("min_nights"))
            assertEquals("active", obj["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects invalid date window`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-invalid-window", name = "Invalid Window")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-06", "end_date": "2026-07-04", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_date_window", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST ignores removed date fields`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-removed-create", name = "Removed Create")
            val body =
                """
                {
                  "poi_id": $poiId,
                  "start_date": "2026-07-04",
                  "end_date": "2026-07-06",
                  "targetDates": ["2026-07-04", "2026-07-05"],
                  "minNights": 2,
                  "cadence_sec": 60,
                  "trigger_kinds": ["atc"]
                }
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals(poiId, obj["poi_id"]!!.jsonPrimitive.long)
            assertEquals(false, obj.containsKey("targetDates"))
            assertEquals(false, obj.containsKey("minNights"))
        }

    @Test
    fun `POST rejects missing scope`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val body =
                """
                {"start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_scope", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects atc watch when booking capability is unsupported`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchServiceRejectingAtc(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-atc-unsupported", name = "Unsupported ATC")
            val campsiteId = seedCampsite(vendorId = "unsupported-atc")
            linkCampsiteToPoi(campsiteId, poiId)
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()

            val resp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }

            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("unsupported_trigger", obj["error"]!!.jsonPrimitive.content)
            assertEquals(0, AvailabilityWatchRepo(ctx).count())
        }

    @Test
    fun `POST with an explicit targets array persists a multi-target watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiA = seedPoi(sourceId = "p-targets-a", name = "Upper Pines")
            val poiB = seedPoi(sourceId = "p-targets-b", name = "Lower Pines")
            val body =
                """
                {
                  "targets": [{"poi_id": $poiA}, {"poi_id": $poiB}],
                  "start_date": "2026-07-04",
                  "end_date": "2026-07-06",
                  "cadence_sec": 60,
                  "trigger_kinds": ["atc"]
                }
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            val targets = obj["targets"]!!.jsonArray
            assertEquals(2, targets.size)
            assertEquals(setOf(poiA, poiB), targets.map { it.jsonObject["poi_id"]!!.jsonPrimitive.long }.toSet())
            // Derived convenience field: first target.
            assertEquals(poiA, obj["poi_id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `POST with legacy poi_id is accepted as a one-element target list`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-legacy-single", name = "Legacy Single")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.Created, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            val targets = obj["targets"]!!.jsonArray
            assertEquals(1, targets.size)
            assertEquals(poiId, targets[0].jsonObject["poi_id"]!!.jsonPrimitive.long)
            assertEquals(poiId, obj["poi_id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `POST rejects both targets and legacy poi_id set together`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-conflict", name = "Conflict")
            val body =
                """
                {"targets": [{"poi_id": $poiId}], "poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_scope", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST rejects a target with both poi_id and campsite_id set`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-bad-target", name = "Bad Target")
            val campsiteId = seedCampsite("bad-target-1")
            linkCampsiteToPoi(campsiteId, poiId)
            val body =
                """
                {"targets": [{"poi_id": $poiId, "campsite_id": $campsiteId}], "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_scope", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET list filters by status`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p2", name = "Glacier")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            repeat(3) {
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val resp = client.get("$WATCHES_PATH?status=active") { asUser(USER_TOKEN) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(3, obj["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `POST modify pauses a watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p3", name = "Yosemite")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long
            val resp =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"status": "paused"}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals("paused", obj["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST modify rejects invalid cadence and triggers`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-invalid-patch", name = "Invalid Patch")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val badCadence =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"cadence_sec": 1}""")
                }
            assertEquals(HttpStatusCode.BadRequest, badCadence.status)
            assertEquals(
                "invalid_cadence",
                Json
                    .parseToJsonElement(badCadence.bodyAsText())
                    .jsonObject["error"]!!
                    .jsonPrimitive.content,
            )

            val badTriggers =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger_kinds": []}""")
                }
            assertEquals(HttpStatusCode.BadRequest, badTriggers.status)
            assertEquals(
                "invalid_triggers",
                Json
                    .parseToJsonElement(badTriggers.bodyAsText())
                    .jsonObject["error"]!!
                    .jsonPrimitive.content,
            )

            val emailTrigger =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger_kinds": ["email_notify"]}""")
                }
            assertEquals(HttpStatusCode.OK, emailTrigger.status)

            val badConfig =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger_config": {"slack_notify": {"channel": ""}}}""")
                }
            assertEquals(HttpStatusCode.BadRequest, badConfig.status)
            assertEquals(
                "invalid_trigger_config",
                Json
                    .parseToJsonElement(badConfig.bodyAsText())
                    .jsonObject["error"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `POST modify updates trigger config and stop when triggered`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-trigger-patch", name = "Trigger Patch")
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["slack_notify"]}
                        """.trimIndent(),
                    )
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val resp =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "trigger_kinds": ["slack_notify", "email_notify", "atc"],
                          "trigger_config": {
                            "slack_notify": {"channel": "#camping"}
                          },
                          "stop_when_triggered": false
                        }
                        """.trimIndent(),
                    )
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val watch = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals(listOf("slack_notify", "email_notify", "atc"), watch["trigger_kinds"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(
                "#camping",
                watch["trigger_config"]!!
                    .jsonObject["slack_notify"]!!
                    .jsonObject["channel"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(null, watch["trigger_config"]!!.jsonObject["email_notify"])
            assertEquals(false, watch["stop_when_triggered"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `POST modify ignores removed date fields`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-removed-patch", name = "Removed Patch")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val resp =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"target_dates": ["2026-07-04"], "min_nights": 1}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals(id, obj["id"]!!.jsonPrimitive.long)
            assertEquals(false, obj.containsKey("target_dates"))
            assertEquals(false, obj.containsKey("min_nights"))
        }

    @Test
    fun `POST modify rejects targets as immutable`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-patch-empty-targets", name = "Patch Empty Targets")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val resp =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"targets": []}""")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("immutable_field", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST modify rejects dates as immutable`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-patch-bad-target", name = "Patch Bad Target")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val resp =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"start_date": "2026-08-01", "end_date": "2026-08-02"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("immutable_field", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `POST modify allows updating trigger config without targets or dates`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p-patch-targets-a", name = "Patch Targets A")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val resp =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger_kinds": ["slack_notify"], "stop_when_triggered": false}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals(false, obj["stop_when_triggered"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `POST delete removes a watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "p4", name = "Tunnel")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long
            val del = client.post(deleteWatchPath(id)) { asUser(USER_TOKEN) }
            assertEquals(HttpStatusCode.NoContent, del.status)
            val getAfter = client.get(watchPath(id)) { asUser(USER_TOKEN) }
            assertEquals(HttpStatusCode.NotFound, getAfter.status)
        }

    @Test
    fun `GET watch includes watch capabilities when configured`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchServiceWithRecgov(),
                        watchCapabilitiesWithRecgov(),
                    )
                }
            }
            seedUsers()
            val poiId =
                seedPoi(
                    sourceId = "p-capabilities",
                    name = "Capabilities",
                    providerRefJson = """{"recgov_id": "232447"}""",
                    bookingProvider = "recgov",
                    bookingProviderRef = "232447",
                )
            linkCampsiteToPoi(seedCampsite(vendorId = "cap-100"), poiId)
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["slack_notify"]}
                        """.trimIndent(),
                    )
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val resp = client.get(watchPath(id)) { asUser(USER_TOKEN) }

            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val capabilities = body["watch_capabilities"]!!.jsonObject
            assertEquals(listOf("slack_notify", "email_notify"), capabilities["trigger_kinds"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertTrue(capabilities["booking_actions"]!!.jsonArray.isEmpty())
        }

    @Test
    fun `POST links a poller and POST modify paused drops the link and deactivates it`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication {
                    availabilityWatchRoutes(ctx, watchServiceWithRecgov())
                }
            }
            seedUsers()
            // POI with a resolvable recgov provider_ref + a child reservable so the
            // watch resolves to exactly one (recgov, 232447) poller.
            val poiId =
                seedPoi(
                    sourceId = "p99",
                    name = "Atomic",
                    providerRefJson = """{"recgov_id": "232447"}""",
                    bookingProvider = "recgov",
                    bookingProviderRef = "232447",
                )
            linkCampsiteToPoi(seedCampsite(vendorId = "100"), poiId)
            val createBody =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
            val watchId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val pollerRepo = AvailabilityPollerRepo(ctx)
            // An active watch is linked to exactly one active poller.
            val linked = pollerRepo.pollerIdsForWatch(watchId)
            assertEquals(1, linked.size)
            assertTrue(pollerRepo.findById(linked.single())!!.active)

            val paused =
                client.post(modifyWatchPath(watchId)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"status": "paused"}""")
                }
            assertEquals(HttpStatusCode.OK, paused.status)

            // Pausing drops the watch's poller links; the now-orphaned poller goes dormant.
            assertTrue(pollerRepo.pollerIdsForWatch(watchId).isEmpty())
            assertEquals(false, pollerRepo.findById(linked.single())!!.active)
        }

    @Test
    fun `GET watches anonymous returns 401`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            assertEquals(HttpStatusCode.Unauthorized, client.get(WATCHES_PATH).status)
        }

    @Test
    fun `GET watches lists only the caller's own`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "mine", name = "Mine")
            client.post(WATCHES_PATH) {
                asUser(USER_TOKEN)
                contentType(ContentType.Application.Json)
                setBody(createBody(poiId))
            }
            client.post(WATCHES_PATH) {
                asUser(OTHER_TOKEN)
                contentType(ContentType.Application.Json)
                setBody(createBody(poiId))
            }
            val resp = client.get(WATCHES_PATH) { asUser(USER_TOKEN) }
            val watches = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watches"]!!.jsonArray
            assertEquals(1, watches.size)
        }

    // ---- magic links: what the link unlocks, and what it must not ----------

    /** Creates a watch owned by [owner] and returns its id. */
    private suspend fun io.ktor.client.HttpClient.createWatchFor(
        owner: String,
        poiId: Long,
    ): Long {
        val created =
            post(WATCHES_PATH) {
                asUser(owner)
                contentType(ContentType.Application.Json)
                setBody(createBody(poiId))
            }
        assertEquals(HttpStatusCode.Created, created.status)
        return Json
            .parseToJsonElement(created.bodyAsText())
            .jsonObject["watch"]!!
            .jsonObject["id"]!!
            .jsonPrimitive.long
    }

    private fun magicLinkTokenFor(watchId: Long): String = magicLinkTokens(ctx).issue(watchId)!!

    private fun withToken(
        path: String,
        token: String,
    ): String = "$path?$MAGIC_LINK_TOKEN_PARAM=$token"

    @Test
    fun `GET with a manage token reads the watch without a session`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-get", name = "Link Get")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            val resp = client.get(withToken(watchPath(id), magicLinkTokenFor(id)))

            assertEquals(HttpStatusCode.OK, resp.status)
            val watch = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals(id, watch["id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `GET without a session or token is 401, not 404`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-anon", name = "Link Anon")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            assertEquals(HttpStatusCode.Unauthorized, client.get(watchPath(id)).status)
        }

    @Test
    fun `a manage token pauses its own watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-pause", name = "Link Pause")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            val resp =
                client.post(withToken(modifyWatchPath(id), magicLinkTokenFor(id))) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"paused"}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
            val watch = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals("paused", watch["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a manage token stops its own watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-stop", name = "Link Stop")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            val resp = client.post(withToken(deleteWatchPath(id), magicLinkTokenFor(id)))

            assertEquals(HttpStatusCode.NoContent, resp.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(watchPath(id)).status)
        }

    @Test
    fun `stopping a watch retires its link`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-retire", name = "Link Retire")
            val id = client.createWatchFor(USER_TOKEN, poiId)
            val token = magicLinkTokenFor(id)

            assertEquals(HttpStatusCode.NoContent, client.post(withToken(deleteWatchPath(id), token)).status)

            // The token lived on the row, so deleting the watch is the only
            // thing that makes copies of that email inert.
            assertEquals(HttpStatusCode.Unauthorized, client.get(withToken(watchPath(id), token)).status)
        }

    @Test
    fun `a manage token is scoped to one watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-scope", name = "Link Scope")
            val mine = client.createWatchFor(USER_TOKEN, poiId)
            val theirs = client.createWatchFor(OTHER_TOKEN, poiId)

            val resp = client.get(withToken(watchPath(theirs), magicLinkTokenFor(mine)))

            // Same answer a forged token gets. Matching on (id, token) together
            // means "valid, but not for this watch" is not a distinguishable
            // state, so a link holder cannot probe which watches their token
            // does not open.
            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `a manage token cannot list watches`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-list", name = "Link List")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            // Session-only: the list route never reads the parameter.
            val resp = client.get("$WATCHES_PATH?$MAGIC_LINK_TOKEN_PARAM=${magicLinkTokenFor(id)}")

            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `a manage token cannot create a watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-create", name = "Link Create")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            val resp =
                client.post("$WATCHES_PATH?$MAGIC_LINK_TOKEN_PARAM=${magicLinkTokenFor(id)}") {
                    contentType(ContentType.Application.Json)
                    setBody(createBody(poiId))
                }

            assertEquals(HttpStatusCode.Unauthorized, resp.status)
        }

    @Test
    fun `a manage token cannot read where the alerts go`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-read-delivery", name = "Link Read Delivery")
            val id = client.createWatchFor(USER_TOKEN, poiId)
            client.post(modifyWatchPath(id)) {
                asUser(USER_TOKEN)
                contentType(ContentType.Application.Json)
                setBody("""{"trigger_config":{"slack_notify":{"channel":"#owner-private"}}}""")
            }
            val token = magicLinkTokenFor(id)

            // Blocking the write is only half of it: the owner's channel must not
            // come back on the read either, or a forwarded link discloses it.
            val viaLink = client.get(withToken(watchPath(id), token)).bodyAsText()
            assertFalse(viaLink.contains("owner-private"), viaLink)

            // The owner still sees their own config.
            val viaSession = client.get(watchPath(id)) { asUser(USER_TOKEN) }.bodyAsText()
            assertTrue(viaSession.contains("owner-private"), viaSession)

            // And it is redacted on the write response a link gets back too.
            val afterPause =
                client
                    .post(withToken(modifyWatchPath(id), token)) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"status":"paused"}""")
                    }.bodyAsText()
            assertFalse(afterPause.contains("owner-private"), afterPause)
        }

    @Test
    fun `a manage token cannot redirect where the alerts go`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-redirect", name = "Link Redirect")
            val id = client.createWatchFor(USER_TOKEN, poiId)
            val token = magicLinkTokenFor(id)

            val channel =
                client.post(withToken(modifyWatchPath(id), token)) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger_config":{"slack_notify":{"channel":"#attacker"}}}""")
                }
            assertEquals(HttpStatusCode.Forbidden, channel.status)

            val kinds =
                client.post(withToken(modifyWatchPath(id), token)) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger_kinds":["slack_notify"]}""")
                }
            assertEquals(HttpStatusCode.Forbidden, kinds.status)
        }

    @Test
    fun `the owner may still change delivery on their own watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "owner-delivery", name = "Owner Delivery")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            val resp =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger_config":{"slack_notify":{"channel":"#mine"}}}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `an unknown token is refused`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-forged", name = "Link Forged")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            assertEquals(HttpStatusCode.Unauthorized, client.get(withToken(watchPath(id), "not-a-token")).status)
        }

    @Test
    fun `the watch API never returns the link token`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-no-leak", name = "Link No Leak")
            val id = client.createWatchFor(USER_TOKEN, poiId)
            val token = magicLinkTokenFor(id)

            // `baseSelect` pulls every column, so the only thing keeping the
            // token out of a response is that `Watch` has no field for it — an
            // addition to the model would hand it to the list route.
            val detail = client.get(watchPath(id)) { asUser(USER_TOKEN) }.bodyAsText()
            val list = client.get(WATCHES_PATH) { asUser(USER_TOKEN) }.bodyAsText()

            assertFalse(detail.contains(token), detail)
            assertFalse(list.contains(token), list)
            assertFalse(detail.contains("magic_link_token"), detail)
        }

    @Test
    fun `every message for a watch carries the same link`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-stable", name = "Link Stable")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            // If minting were not idempotent, the second alert would invalidate
            // the link in the first.
            assertEquals(magicLinkTokenFor(id), magicLinkTokenFor(id))
        }

    @Test
    fun `a link keeps working after it has been used`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-reuse", name = "Link Reuse")
            val id = client.createWatchFor(USER_TOKEN, poiId)
            val token = magicLinkTokenFor(id)

            // Not single-use: pause today, resume from the same email tomorrow.
            assertEquals(HttpStatusCode.OK, client.get(withToken(watchPath(id), token)).status)
            assertEquals(
                HttpStatusCode.OK,
                client
                    .post(withToken(modifyWatchPath(id), token)) {
                        contentType(ContentType.Application.Json)
                        setBody("""{"status":"paused"}""")
                    }.status,
            )
            assertEquals(HttpStatusCode.OK, client.get(withToken(watchPath(id), token)).status)
        }

    @Test
    fun `a session for the owner wins over a token for someone else's watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-both", name = "Link Both")
            val mine = client.createWatchFor(USER_TOKEN, poiId)

            // Session grant wins, so delivery changes are still allowed.
            val resp =
                client.post(withToken(modifyWatchPath(mine), magicLinkTokenFor(mine))) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger_config":{"slack_notify":{"channel":"#mine"}}}""")
                }

            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `a link works for a visitor signed in as somebody else`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "link-forwarded", name = "Link Forwarded")
            val id = client.createWatchFor(USER_TOKEN, poiId)

            // Possession is the credential; another account must not shadow it.
            val resp =
                client.get(withToken(watchPath(id), magicLinkTokenFor(id))) { asUser(OTHER_TOKEN) }

            assertEquals(HttpStatusCode.OK, resp.status)
        }

    @Test
    fun `GET of another user's watch returns 404`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "theirs-get", name = "Theirs Get")
            val created =
                client.post(WATCHES_PATH) {
                    asUser(OTHER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(createBody(poiId))
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long
            val resp = client.get(watchPath(id)) { asUser(USER_TOKEN) }
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `POST modify of another user's watch returns 404`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "theirs-modify", name = "Theirs Modify")
            val created =
                client.post(WATCHES_PATH) {
                    asUser(OTHER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(createBody(poiId))
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long
            val resp =
                client.post(modifyWatchPath(id)) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"status":"paused"}""")
                }
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `POST delete of another user's watch returns 404`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "theirs", name = "Theirs")
            val created =
                client.post(WATCHES_PATH) {
                    asUser(OTHER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(createBody(poiId))
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long
            val resp = client.post(deleteWatchPath(id)) { asUser(USER_TOKEN) }
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `admin GET lists all users watches`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "admin-list", name = "Admin List")

            // OTHER creates a watch
            val otherResp =
                client.post(WATCHES_PATH) {
                    asUser(OTHER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(createBody(poiId))
                }
            val otherWatchId =
                Json
                    .parseToJsonElement(otherResp.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            // USER creates a watch
            val userResp =
                client.post(WATCHES_PATH) {
                    asUser(USER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(createBody(poiId))
                }
            val userWatchId =
                Json
                    .parseToJsonElement(userResp.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            // Admin lists all watches and sees both
            val resp = client.get(WATCHES_PATH) { asUser(ADMIN_TOKEN) }
            assertEquals(HttpStatusCode.OK, resp.status)
            val watches = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watches"]!!.jsonArray
            assertTrue(watches.size >= 2, "Admin should see at least 2 watches (from OTHER and USER)")

            // Verify watches from both users are present by checking IDs
            val watchIds = watches.map { it.jsonObject["id"]!!.jsonPrimitive.long }.toSet()
            assertTrue(watchIds.contains(otherWatchId), "Admin should see OTHER's watch")
            assertTrue(watchIds.contains(userWatchId), "Admin should see USER's watch")
        }

    @Test
    fun `admin can delete another user's watch`() =
        testApplication {
            application {
                install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
                routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
            }
            seedUsers()
            val poiId = seedPoi(sourceId = "admin-delete", name = "Admin Delete")

            // OTHER creates a watch
            val created =
                client.post(WATCHES_PATH) {
                    asUser(OTHER_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody(createBody(poiId))
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            // Admin deletes OTHER's watch
            val resp = client.post(deleteWatchPath(id)) { asUser(ADMIN_TOKEN) }
            assertEquals(HttpStatusCode.NoContent, resp.status)

            // Verify the watch is deleted
            val getAfter = client.get(watchPath(id)) { asUser(ADMIN_TOKEN) }
            assertEquals(HttpStatusCode.NotFound, getAfter.status)
        }

    private fun seedPoi(
        sourceId: String,
        name: String,
        providerRefJson: String? = null,
        bookingProvider: String? = null,
        bookingProviderRef: String? = null,
    ): Long =
        ctx
            .seedCatalogPoi(
                sourceId = sourceId,
                name = name,
                lon = -119.56,
                lat = 37.74,
                source = "recgov",
                providerRefJson = providerRefJson,
                bookingProvider = bookingProvider,
                bookingProviderRef = bookingProviderRef,
            ).poiId

    private fun seedCampsite(
        vendorId: String,
        name: String? = null,
        loop: String? = null,
        siteType: String? = null,
    ): Long =
        ctx.seedCampsite(
            campgroundId = ctx.seedCampground(name = "Route Watch Campground", source = "recgov", sourceId = "route-watch-$vendorId"),
            vendor = "recgov",
            vendorId = vendorId,
            name = name ?: "Site $vendorId",
            kind = siteType ?: "site",
            loopName = loop,
        )

    private fun linkCampsiteToPoi(
        campsiteId: Long,
        poiId: Long,
    ) {
        ctx.execute(
            """
            UPDATE campsites
            SET campground_id = (
              SELECT campground_id
              FROM poi_campgrounds
              WHERE poi_id = ?
            )
            WHERE id = ?
            """.trimIndent(),
            poiId,
            campsiteId,
        )
    }
}

/**
 * Minimal recgov adapter for membership resolution in these route tests. It
 * never fetches (the watch service only resolves targets, it does not poll),
 * so the availability methods are unsupported.
 */
private object FakeRecgovProvider : ca.floo.roadtrip.service.availability.provider.AvailabilityProvider {
    override val id = BookingProvider.RECGOV
    override val capabilities =
        ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = 180,
            maxPollWindowDays = 60,
        )

    override fun isEnabled(): Boolean = true

    override suspend fun availability(
        campground: Campground,
        startDate: java.time.LocalDate,
        endDate: java.time.LocalDate,
    ): ca.floo.roadtrip.model.availability.AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
}
