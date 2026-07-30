package ca.floo.roadtrip.route

import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
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
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlin.test.assertTrue
import ca.floo.roadtrip.route.api.availability.availabilityWatchRoutes as installAvailabilityWatchRoutes

private const val WATCHES_PATH = "/api/watches"
private const val MODIFY_ACTION = "modify"
private const val DELETE_ACTION = "delete"

private fun watchPath(id: Long): String = "$WATCHES_PATH/$id"

private fun modifyWatchPath(id: Long): String = "${watchPath(id)}/$MODIFY_ACTION"

private fun deleteWatchPath(id: Long): String = "${watchPath(id)}/$DELETE_ACTION"

class AvailabilityWatchRoutesTest : SharedDbTest() {
    @BeforeEach
    fun cleanup() {
        ctx.cleanCanonicalCatalogFixtures()
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
        return AvailabilityWatchController(
            watchRepo = AvailabilityWatchRepo(ctx),
            watchService = watchService,
            watchMapper =
                AvailabilityWatchApiMapper(
                    campsiteRepo = campsitesRepo,
                    scopeResolver = WatchScopeResolver(campsitesRepo),
                    watchCapabilityService = watchCapabilities,
                ),
        )
    }

    @Test
    fun `POST creates a poi-scoped watch with filters`() =
        testApplication {
            application {
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-invalid-window", name = "Invalid Window")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-06", "end_date": "2026-07-04", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val body =
                """
                {"start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchServiceRejectingAtc(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-atc-unsupported", name = "Unsupported ATC")
            val campsiteId = seedCampsite(vendorId = "unsupported-atc")
            linkCampsiteToPoi(campsiteId, poiId)
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()

            val resp =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-legacy-single", name = "Legacy Single")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-conflict", name = "Conflict")
            val body =
                """
                {"targets": [{"poi_id": $poiId}], "poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-bad-target", name = "Bad Target")
            val campsiteId = seedCampsite("bad-target-1")
            linkCampsiteToPoi(campsiteId, poiId)
            val body =
                """
                {"targets": [{"poi_id": $poiId, "campsite_id": $campsiteId}], "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p2", name = "Glacier")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            repeat(3) {
                client.post(WATCHES_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val resp = client.get("$WATCHES_PATH?status=active")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(3, obj["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `POST modify pauses a watch`() =
        testApplication {
            application {
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p3", name = "Yosemite")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-invalid-patch", name = "Invalid Patch")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
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

            val badEmailTrigger =
                client.post(modifyWatchPath(id)) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"trigger_kinds": ["email_notify"]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, badEmailTrigger.status)
            assertEquals(
                "invalid_trigger_config",
                Json
                    .parseToJsonElement(badEmailTrigger.bodyAsText())
                    .jsonObject["error"]!!
                    .jsonPrimitive.content,
            )

            val badConfig =
                client.post(modifyWatchPath(id)) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-trigger-patch", name = "Trigger Patch")
            val created =
                client.post(WATCHES_PATH) {
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
                    contentType(ContentType.Application.Json)
                    setBody(
                        """
                        {
                          "trigger_kinds": ["slack_notify", "email_notify", "atc"],
                          "trigger_config": {
                            "slack_notify": {"channel": "#camping"},
                            "email_notify": {"to": "alerts@example.test"}
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
            assertEquals(
                "alerts@example.test",
                watch["trigger_config"]!!
                    .jsonObject["email_notify"]!!
                    .jsonObject["to"]!!
                    .jsonPrimitive.content,
            )
            assertEquals(false, watch["stop_when_triggered"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `POST modify ignores removed date fields`() =
        testApplication {
            application {
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-removed-patch", name = "Removed Patch")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-patch-empty-targets", name = "Patch Empty Targets")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-patch-bad-target", name = "Patch Bad Target")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-patch-targets-a", name = "Patch Targets A")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
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
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p4", name = "Tunnel")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post(WATCHES_PATH) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long
            val del = client.post(deleteWatchPath(id))
            assertEquals(HttpStatusCode.NoContent, del.status)
            val getAfter = client.get(watchPath(id))
            assertEquals(HttpStatusCode.NotFound, getAfter.status)
        }

    @Test
    fun `GET watch includes watch capabilities when configured`() =
        testApplication {
            application {
                routeTestApplication {
                    availabilityWatchRoutes(
                        ctx,
                        watchServiceWithRecgov(),
                        watchCapabilitiesWithRecgov(),
                    )
                }
            }
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

            val resp = client.get(watchPath(id))

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
                routeTestApplication {
                    availabilityWatchRoutes(ctx, watchServiceWithRecgov())
                }
            }
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
                    contentType(ContentType.Application.Json)
                    setBody("""{"status": "paused"}""")
                }
            assertEquals(HttpStatusCode.OK, paused.status)

            // Pausing drops the watch's poller links; the now-orphaned poller goes dormant.
            assertTrue(pollerRepo.pollerIdsForWatch(watchId).isEmpty())
            assertEquals(false, pollerRepo.findById(linked.single())!!.active)
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
