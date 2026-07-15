package ca.floo.roadtrip.routes

import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.SharedDbTest
import ca.floo.roadtrip.repo.cleanCanonicalCatalogFixtures
import ca.floo.roadtrip.repo.seedCampground
import ca.floo.roadtrip.repo.seedCampsite
import ca.floo.roadtrip.repo.seedCatalogPoi
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityDateResolver
import ca.floo.roadtrip.service.availability.AvailabilityPollerMembership
import ca.floo.roadtrip.service.availability.AvailabilityWatchService
import ca.floo.roadtrip.service.availability.DbAvailabilityTargetResolver
import ca.floo.roadtrip.service.availability.SlackNotifyHandler
import ca.floo.roadtrip.service.availability.TriggerActionRegistry
import ca.floo.roadtrip.service.availability.WatchAlertDispatcher
import ca.floo.roadtrip.service.availability.WatchCapabilityService
import ca.floo.roadtrip.service.availability.WatchScopeResolver
import ca.floo.roadtrip.service.availability.WatchTriggerCapabilityValidator
import ca.floo.roadtrip.service.availability.alert.AlertProviderRegistry
import ca.floo.roadtrip.service.availability.alert.InternalPollerAlertProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderRegistry
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.notification.SlackNotificationServiceImpl
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                providerRefs = CampsiteProviderRepo(ctx),
                campsitesRepo = campsitesRepo,
                availabilityProviders = AvailabilityProviderRegistry(emptyMap()),
                dateResolver = AvailabilityDateResolver(),
            )
        return AvailabilityWatchService(ctx, alertProviders(campsitesRepo, targets))
    }

    /**
     * Watch service whose registry maps the test POI source ('test') to a
     * recgov adapter, so a POI with a `{"recgov_id": ...}` provider_ref
     * resolves to a real (recgov, parentRef) poller. Used to exercise poller
     * membership on watch mutation.
     */
    private fun watchServiceWithRecgov(): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val registry = AvailabilityProviderRegistry(mapOf("test" to FakeRecgovProvider))
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                campsitesRepo = campsitesRepo,
                availabilityProviders = registry,
                dateResolver = AvailabilityDateResolver(),
            )
        return AvailabilityWatchService(ctx, alertProviders(campsitesRepo, targets))
    }

    private fun watchServiceRejectingAtc(): AvailabilityWatchService {
        val campsitesRepo = CampsiteRepo(ctx)
        val targets =
            DbAvailabilityTargetResolver(
                providerRefs = CampsiteProviderRepo(ctx),
                campsitesRepo = campsitesRepo,
                availabilityProviders = AvailabilityProviderRegistry(emptyMap()),
                dateResolver = AvailabilityDateResolver(),
            )
        val scopeResolver = WatchScopeResolver(campsitesRepo)
        return AvailabilityWatchService(
            ctx = ctx,
            alertProviders = alertProviders(campsitesRepo, targets),
            capabilityValidator =
                WatchTriggerCapabilityValidator(
                    scopeResolver = scopeResolver,
                    capabilities =
                        WatchCapabilityService(
                            availabilityTargets = targets,
                            bookingTargets = AvailabilityBookingTargetResolver(BookingProviderRegistry(emptyList())),
                        ),
                ),
        )
    }

    /** Wraps the default internal-poller alert provider for tests, mirroring
     *  the production wiring in [ca.floo.roadtrip.startRoadtripRuntime]. */
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

    // These CRUD tests don't exercise Slack, so the dispatcher runs with the
    // notifier disabled — dispatchInitial no-ops and the fire-and-forget launch
    // does nothing. Initial-notify behavior is covered in AvailabilityPollExecutorTest.
    private val testNotifyScope = CoroutineScope(Dispatchers.Unconfined)

    private fun disabledDispatcher(): WatchAlertDispatcher {
        val campsitesRepo = CampsiteRepo(ctx)
        val slack = SlackNotificationServiceImpl(config = null)
        return WatchAlertDispatcher(
            slack = slack,
            scopeResolver = WatchScopeResolver(campsitesRepo),
            watches = AvailabilityWatchRepo(ctx),
            targets =
                DbAvailabilityTargetResolver(
                    providerRefs = CampsiteProviderRepo(ctx),
                    campsitesRepo = campsitesRepo,
                    availabilityProviders = AvailabilityProviderRegistry(emptyMap()),
                    dateResolver = AvailabilityDateResolver(),
                ),
            pois = PoiServingRepo(ctx),
            availability = AvailabilityRepo(ctx),
            triggerActions = TriggerActionRegistry(listOf(SlackNotifyHandler(slack, appRootUrl = null))),
            grafanaRootUrl = null,
            appRootUrl = null,
        )
    }

    @Test
    fun `POST creates a poi-scoped watch with filters`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
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
                client.post("/api/availability/watches") {
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
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-invalid-window", name = "Invalid Window")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-06", "end_date": "2026-07-04", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
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
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
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
                client.post("/api/availability/watches") {
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
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val body =
                """
                {"start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
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
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchServiceRejectingAtc(),
                        disabledDispatcher(),
                        testNotifyScope,
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
                client.post("/api/availability/watches") {
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
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
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
                client.post("/api/availability/watches") {
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
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-legacy-single", name = "Legacy Single")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
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
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-conflict", name = "Conflict")
            val body =
                """
                {"targets": [{"poi_id": $poiId}], "poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val resp =
                client.post("/api/availability/watches") {
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
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
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
                client.post("/api/availability/watches") {
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
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p2", name = "Glacier")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            repeat(3) {
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
            val resp = client.get("/api/availability/watches?status=active")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(3, obj["total"]!!.jsonPrimitive.int)
        }

    @Test
    fun `PATCH pauses a watch`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p3", name = "Yosemite")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
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
                client.patch("/api/availability/watches/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status": "paused"}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            assertEquals("paused", obj["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PATCH rejects invalid cadence and triggers`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-invalid-patch", name = "Invalid Patch")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
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
                client.patch("/api/availability/watches/$id") {
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
                client.patch("/api/availability/watches/$id") {
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
        }

    @Test
    fun `PATCH ignores removed date fields`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-removed-patch", name = "Removed Patch")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
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
                client.patch("/api/availability/watches/$id") {
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
    fun `PATCH rejects an empty targets array`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-patch-empty-targets", name = "Patch Empty Targets")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
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
                client.patch("/api/availability/watches/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"targets": []}""")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_scope", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PATCH rejects a target with both poi_id and campsite_id set`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p-patch-bad-target", name = "Patch Bad Target")
            val campsiteId = seedCampsite("patch-bad-target-1")
            linkCampsiteToPoi(campsiteId, poiId)
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
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
                client.patch("/api/availability/watches/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"targets": [{"poi_id": $poiId, "campsite_id": $campsiteId}]}""")
                }
            assertEquals(HttpStatusCode.BadRequest, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("invalid_scope", obj["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `PATCH with a valid single-target targets array updates the watch`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiA = seedPoi(sourceId = "p-patch-targets-a", name = "Patch Targets A")
            val poiB = seedPoi(sourceId = "p-patch-targets-b", name = "Patch Targets B")
            val body =
                """
                {"poi_id": $poiA, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
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
                client.patch("/api/availability/watches/$id") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"targets": [{"poi_id": $poiB}]}""")
                }
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watch"]!!.jsonObject
            val targets = obj["targets"]!!.jsonArray
            assertEquals(1, targets.size)
            assertEquals(poiB, targets[0].jsonObject["poi_id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `DELETE removes a watch`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p4", name = "Tunnel")
            val body =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val id =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long
            val del = client.delete("/api/availability/watches/$id")
            assertEquals(HttpStatusCode.NoContent, del.status)
            val getAfter = client.get("/api/availability/watches/$id")
            assertEquals(HttpStatusCode.NotFound, getAfter.status)
        }

    @Test
    fun `POST links a poller and PATCH paused drops the link and deactivates it`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(ctx, watchServiceWithRecgov(), disabledDispatcher(), testNotifyScope)
                }
            }
            // POI with a resolvable recgov provider_ref + a child reservable so the
            // watch resolves to exactly one (recgov, 232447) poller.
            val poiId = seedPoi(sourceId = "p99", name = "Atomic", providerRefJson = """{"recgov_id": "232447"}""")
            linkCampsiteToPoi(seedCampsite(vendorId = "100"), poiId)
            val createBody =
                """
                {"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
            val watchId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val pollers = AvailabilityPollerRepo(ctx)
            // An active watch is linked to exactly one active poller.
            val linked = pollers.pollerIdsForWatch(watchId)
            assertEquals(1, linked.size)
            assertTrue(pollers.findById(linked.single())!!.active)

            val paused =
                client.patch("/api/availability/watches/$watchId") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"status": "paused"}""")
                }
            assertEquals(HttpStatusCode.OK, paused.status)

            // Pausing drops the watch's poller links; the now-orphaned poller goes dormant.
            assertTrue(pollers.pollerIdsForWatch(watchId).isEmpty())
            assertEquals(false, pollers.findById(linked.single())!!.active)
        }

    private fun seedPoi(
        sourceId: String,
        name: String,
        providerRefJson: String? = null,
    ): Long =
        ctx
            .seedCatalogPoi(
                sourceId = sourceId,
                name = name,
                lon = -119.56,
                lat = 37.74,
                source = "test",
                providerRefJson = providerRefJson,
            ).poiId

    private fun seedCampsite(
        vendorId: String,
        name: String? = null,
        loop: String? = null,
        siteType: String? = null,
    ): Long =
        ctx.seedCampsite(
            campgroundId = ctx.seedCampground(name = "Route Watch Campground", source = "test", sourceId = "route-watch-$vendorId"),
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

    /** Seeds one interval row -- the heatmap's current-state source of truth --
     *  as a single status-run for the cell. */
    private fun insertCell(
        campsiteId: Long,
        targetDate: String,
        observedAt: java.time.OffsetDateTime,
        available: Boolean,
    ) {
        ctx.execute(
            """
            INSERT INTO availability (
                campsite_id, target_date, status, last_observed_at
            ) VALUES (?::bigint, ?::date, ?::availability_status, ?::timestamptz)
            """.trimIndent(),
            campsiteId,
            targetDate,
            if (available) "available" else "reserved",
            observedAt.toString(),
        )
    }

    @Test
    fun `GET watch heatmap returns 404 for unknown id`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val resp = client.get("/api/availability/watches/99999/heatmap")
            assertEquals(HttpStatusCode.NotFound, resp.status)
        }

    @Test
    fun `GET watch heatmap for campsite-scoped watch returns one row`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p1", name = "Upper Pines")
            val campsiteId = seedCampsite("100", name = "A12", loop = "Loop A")
            linkCampsiteToPoi(campsiteId, poiId)

            val createBody =
                """
                {"campsite_id": $campsiteId, "start_date": "2026-07-04", "end_date": "2026-07-06", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
            val watchId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val now = java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC)
            insertCell(campsiteId, "2026-07-04", now.minusMinutes(1), available = true)

            val resp = client.get("/api/availability/watches/$watchId/heatmap")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals(
                listOf("2026-07-04", "2026-07-05"),
                body["dates"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
            val groups = body["groups"]!!.jsonArray
            assertEquals(1, groups.size)
            assertEquals("Loop A", groups[0].jsonObject["loop"]!!.jsonPrimitive.content)
            val rows = groups[0].jsonObject["rows"]!!.jsonArray
            assertEquals(1, rows.size)
            val cells = rows[0].jsonObject["cells"]!!.jsonArray
            assertEquals(2, cells.size)
            assertEquals("available", cells[0].jsonObject["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET watch heatmap for poi-scoped watch filters by loop`() =
        testApplication {
            application {
                routing {
                    availabilityWatchRoutes(
                        ctx,
                        watchService(),
                        disabledDispatcher(),
                        testNotifyScope,
                    )
                }
            }
            val poiId = seedPoi(sourceId = "p2", name = "Tunnel Mountain")
            val rA1 = seedCampsite("201", name = "A12", loop = "Loop A")
            val rA2 = seedCampsite("202", name = "A13", loop = "Loop A")
            val rB1 = seedCampsite("203", name = "B05", loop = "Loop B")
            linkCampsiteToPoi(rA1, poiId)
            linkCampsiteToPoi(rA2, poiId)
            linkCampsiteToPoi(rB1, poiId)

            val createBody =
                """
                {"poi_id": $poiId, "campsite_filters": {"loop": ["Loop A"]}, "start_date": "2026-07-04", "end_date": "2026-07-05", "cadence_sec": 60, "trigger_kinds": ["atc"]}
                """.trimIndent()
            val created =
                client.post("/api/availability/watches") {
                    contentType(ContentType.Application.Json)
                    setBody(createBody)
                }
            val watchId =
                Json
                    .parseToJsonElement(created.bodyAsText())
                    .jsonObject["watch"]!!
                    .jsonObject["id"]!!
                    .jsonPrimitive.long

            val resp = client.get("/api/availability/watches/$watchId/heatmap")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            val groups = body["groups"]!!.jsonArray
            assertEquals(1, groups.size)
            assertEquals("Loop A", groups[0].jsonObject["loop"]!!.jsonPrimitive.content)
            val rows = groups[0].jsonObject["rows"]!!.jsonArray
            assertEquals(2, rows.size)
            val campsiteIdsInResponse = rows.map { it.jsonObject["campsite_id"]!!.jsonPrimitive.long }
            assertEquals(true, campsiteIdsInResponse.contains(rA1))
            assertEquals(true, campsiteIdsInResponse.contains(rA2))
            assertEquals(false, campsiteIdsInResponse.contains(rB1))
        }
}

/**
 * Minimal recgov adapter for membership resolution in these route tests. It
 * never fetches (the watch service only resolves targets, it does not poll),
 * so the availability methods are unsupported.
 */
private object FakeRecgovProvider : ca.floo.roadtrip.service.availability.provider.AvailabilityProvider {
    override val id = ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId.RECGOV
    override val capabilities =
        ca.floo.roadtrip.models.availability.AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = 180,
            maxPollWindowDays = 60,
        )

    override fun isEnabled(): Boolean = true

    override suspend fun availability(
        ref: ca.floo.roadtrip.models.domain.ProviderRef,
        startDate: java.time.LocalDate,
        endDate: java.time.LocalDate,
    ): ca.floo.roadtrip.models.availability.AvailabilityObservationBatch = throw UnsupportedOperationException("not used")
}
