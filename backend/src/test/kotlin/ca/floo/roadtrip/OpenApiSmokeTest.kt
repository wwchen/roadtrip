package ca.floo.roadtrip

import ca.floo.roadtrip.apigen.contractSchemas
import ca.floo.roadtrip.client.mapbox.MapboxDirections
import ca.floo.roadtrip.config.RouteConfig
import ca.floo.roadtrip.fixtures.refsIn
import ca.floo.roadtrip.fixtures.responsesOf
import ca.floo.roadtrip.fixtures.schemaRef
import ca.floo.roadtrip.fixtures.testCampgroundService
import ca.floo.roadtrip.model.api.ApiContract
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.route.api.availability.availabilityDashboardRoutes
import ca.floo.roadtrip.route.api.docs.apiDocsRoutes
import ca.floo.roadtrip.route.api.health.healthRoutes
import ca.floo.roadtrip.route.api.pois.poiRoutes
import ca.floo.roadtrip.route.api.pois.poisOnRouteRoutes
import ca.floo.roadtrip.route.auth.authRoutes
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.service.availability.AvailabilityDashboardController
import ca.floo.roadtrip.service.health.ReadinessService
import ca.floo.roadtrip.service.poi.PlanetFitnessLocationService
import ca.floo.roadtrip.service.poi.PoiService
import ca.floo.roadtrip.service.poi.PoisOnRouteService
import ca.floo.roadtrip.service.poi.TeslaSuperchargerService
import ca.floo.roadtrip.service.routing.RouteCache
import ca.floo.roadtrip.service.routing.RouteCorridorService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SCHEMA_PREFIX = "#/components/schemas/"

/** Long enough that the force route never reports a cooldown of zero; never elapses here. */
private const val FORCE_POLLER_COOLDOWN_SECONDS = 60L

/**
 * The row the access seam is asserted on: a `User`-level stub, so the document's
 * 401 can only have come from `declaredAccessByLeaf` agreeing with the builder's
 * lookup key. Every other route this slice mounts is [RouteAccess.Anonymous].
 */
private const val USER_GATED_PATH = "/api/watches"

/**
 * Where the Swagger UI fetches the spec — `SwaggerConfig.remotePath`'s default,
 * under the UI's own mount. It is the `serializeModel` copy, not the
 * hand-mounted `openapi.json` route, and it is JSON despite the name because
 * `roadtripOpenApiSource` sets the content type.
 */
private const val UI_SPEC_PATH = "/api/docs/documentation.yaml"

/** The one key the two copies of the document differ on. */
private const val WEBHOOKS_KEY = "webhooks"

/** [spec] without an empty `webhooks`, so the two copies can be compared whole. */
private fun withoutEmptyWebhooks(spec: JsonObject): JsonObject =
    if (spec[WEBHOOKS_KEY]?.jsonObject?.isEmpty() == true) JsonObject(spec - WEBHOOKS_KEY) else spec

// Smoke for /api/docs (issue #47).
//
// Boots a slim test app with the same /api/docs and
// /api/docs/openapi.json routes Main.kt mounts. Asserts:
//   - GET /api/docs returns 200 (Swagger UI HTML).
//   - GET /api/docs/openapi.json returns 200 with a parseable spec listing
//     the documented paths and their summaries.
//
// We don't boot the full Application.module() here because that would pull
// in Postgres, Flyway, provider caches, etc. — overkill when we
// just want to verify the plugin wires correctly.
class OpenApiSmokeTest {
    @Test
    fun `swagger UI serves at api docs`() =
        testApplication {
            application {
                routing {
                    apiDocsRoutes()
                }
            }
            val resp = client.get("/api/docs")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            assertTrue(
                body.contains("swagger-ui", ignoreCase = true) ||
                    body.contains("swagger-initializer", ignoreCase = true),
                "Swagger UI HTML missing expected markers; got: ${body.take(200)}",
            )
        }

    @Test
    fun `openapi spec lists representative real routes with summaries and tags`() =
        testApplication {
            application {
                val ctx = DSL.using(SQLDialect.POSTGRES)
                val poiService = testPoiService(ctx)
                val routeCorridorService = RouteCorridorService(RouteCorridorRepo(ctx))
                routing {
                    apiDocsRoutes()
                    get("/") { call.respondText("root") }
                    get("/data/{path...}") { call.respondText("static") }
                    healthRoutes { ReadinessService.Report(databaseReachable = true) }
                    poiRoutes(poiService)
                    poisOnRouteRoutes(
                        PoisOnRouteService(
                            routeCache = RouteCache(MapboxDirections(token = null)),
                            routeCorridorService = routeCorridorService,
                            poiService = poiService,
                        ),
                        routeConfig = testRouteConfig(),
                    )
                    authRoutes(wiring = null)
                    availabilityDashboardRoutes(testDashboardController(ctx))
                }
            }

            val resp = client.get("/api/docs/openapi.json")
            assertEquals(HttpStatusCode.OK, resp.status)

            val spec = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertNotNull(spec["openapi"], "spec missing openapi version")
            val paths = spec["paths"]!!.jsonObject

            val healthGet =
                paths["/api/health"]!!.jsonObject["get"]!!.jsonObject
            assertEquals("Application liveness probe", healthGet["summary"]!!.jsonPrimitive.content)
            assertEquals(
                "health",
                healthGet["tags"]!!
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content,
            )

            // Readiness is a separate documented operation, not a variant of
            // liveness — a deploy gate has to be able to find it in the spec.
            val readyGet = paths["/api/health/ready"]!!.jsonObject["get"]!!.jsonObject
            assertEquals("Application readiness probe (dependency-aware)", readyGet["summary"]!!.jsonPrimitive.content)

            val poisPost =
                paths["/api/pois"]!!.jsonObject["post"]!!.jsonObject
            assertEquals(
                "POIs within bbox; capped at 2000 features (truncated:true on overflow)",
                poisPost["summary"]!!.jsonPrimitive.content,
            )

            val onRoutePost =
                paths["/api/pois/on-route"]!!.jsonObject["post"]!!.jsonObject
            assertEquals(
                "Slim POIs inside a buffered route corridor (no viewport, no truncation)",
                onRoutePost["summary"]!!.jsonPrimitive.content,
            )

            assertFalse(paths.containsKey("/api/availability/bulk"))
            // The redirect rows are outside both contracted prefixes, so mounting
            // authRoutes brings /auth/password/** into the document and nothing else.
            assertFalse(paths.containsKey("/auth/login"))
            assertFalse(paths.containsKey("/api/docs"))
            assertFalse(paths.containsKey("/api/docs/openapi.json"))
            assertFalse(paths.containsKey("/"))
            assertFalse(paths.keys.any { it.startsWith("/data") })
            assertFalse(paths.containsKey("/api/campsite/events"))
            assertFalse(paths.containsKey("/api/campsite/availability/{poi_id}"))
            assertFalse(paths.containsKey("/api/poi/{poi_id}/reservables/availability"))
            assertFalse(paths.containsKey("/api/admin/campsite/debug/synth-match"))
        }

    @Test
    fun `the spec carries the contract's schemas, request bodies and statuses`() =
        testApplication {
            application {
                val ctx = DSL.using(SQLDialect.POSTGRES)
                val poiService = testPoiService(ctx)
                routing {
                    apiDocsRoutes()
                    healthRoutes { ReadinessService.Report(databaseReachable = true) }
                    poiRoutes(poiService)
                    authRoutes(wiring = null)
                    availabilityDashboardRoutes(testDashboardController(ctx))
                    // The one gated leaf in the slice: its 401 is not on the row.
                    get(USER_GATED_PATH) { call.respondText("stub") }.access(RouteAccess.User)
                }
            }

            val spec = Json.parseToJsonElement(client.get("/api/docs/openapi.json").bodyAsText()).jsonObject
            val schemas = spec["components"]!!.jsonObject["schemas"]!!.jsonObject

            // The schemas come from the whole contract, not from what this slice
            // mounts, so every reference in a partial document still resolves.
            assertEquals(contractSchemas().keys, schemas.keys)
            listOf("ApiErrorSchema", "CampsiteDto", "CheckNowCooldownDto", "WatchStatus").forEach { name ->
                assertTrue(name in schemas.keys, "$name missing from components/schemas")
            }
            assertEquals(emptyList(), refsIn(spec).filterNot { it.removePrefix(SCHEMA_PREFIX) in schemas.keys })

            val paths = spec["paths"]!!.jsonObject

            // A request body arrives as a reference to the same declaration the
            // TypeScript names.
            assertEquals(
                "${SCHEMA_PREFIX}PoisRequestSchema",
                schemaRef(
                    paths
                        .getValue("/api/pois")
                        .jsonObject
                        .getValue("post")
                        .jsonObject["requestBody"]!!,
                ),
            )

            // The readiness probe serves the same DTO at both statuses.
            val ready = responsesOf(paths, "/api/health/ready", "get")
            assertEquals("${SCHEMA_PREFIX}ReadinessResponseDto", schemaRef(ready.getValue("200")))
            assertEquals("${SCHEMA_PREFIX}ReadinessResponseDto", schemaRef(ready.getValue("503")))

            assertEquals(setOf("200", "400", "404"), responsesOf(paths, "/api/pois/{id}", "get").keys)

            assertEquals(
                "${SCHEMA_PREFIX}CheckNowCooldownDto",
                schemaRef(responsesOf(paths, "/api/availability/pollers/{id}/force", "post").getValue("429")),
            )

            // /auth/password/** is in the document now, so its two rows carry
            // schemas — and a tag and a summary, which nothing else pins.
            val begin =
                paths
                    .getValue("/auth/password/begin")
                    .jsonObject
                    .getValue("post")
                    .jsonObject
            assertEquals(
                "${SCHEMA_PREFIX}PasswordBeginResponseDto",
                schemaRef(begin.getValue("responses").jsonObject.getValue("200")),
            )
            assertNotNull(begin["summary"], "describeApi's summary reaches the document")
            assertNull(
                responsesOf(paths, "/auth/password/complete", "post").getValue("204").jsonObject["content"],
                "a 204 publishes no content",
            )

            // The access seam, end to end: the row declares no 401, so this one
            // can only come from the route's own RouteAccess.User, which means
            // declaredAccessByLeaf's key and the builder's lookup key agree.
            val gated = responsesOf(paths, USER_GATED_PATH, "get")
            assertEquals(setOf("200", "400", "401"), gated.keys)
            assertEquals("${SCHEMA_PREFIX}ApiErrorSchema", schemaRef(gated.getValue("401")))

            // The copy the Swagger UI renders goes through the same contract pass,
            // and is the artifact a human reads. It is the same document, whole:
            // the only difference is the empty `webhooks` OpenApiDocSource.Routing
            // puts on the model it hands serializeModel, which `explicitNulls =
            // false` drops from the hand-mounted copy.
            val uiSpec = Json.parseToJsonElement(client.get(UI_SPEC_PATH).bodyAsText()).jsonObject
            assertEquals(contractSchemas().keys, uiSpec["components"]!!.jsonObject["schemas"]!!.jsonObject.keys)
            assertEquals(gated, responsesOf(uiSpec["paths"]!!.jsonObject, USER_GATED_PATH, "get"))
            // Present-and-empty is what Ktor puts there today; absent is equally
            // fine and is what the other copy has. Either way it carries nothing,
            // which is the claim — `getValue` would have thrown on the absent case
            // instead of failing with a message.
            val webhooks = uiSpec[WEBHOOKS_KEY]?.jsonObject
            assertTrue(webhooks == null || webhooks.isEmpty(), "the UI copy declares webhooks: $webhooks")
            assertEquals(withoutEmptyWebhooks(spec), withoutEmptyWebhooks(uiSpec))

            // Every mounted row got its responses; nothing in the contracted
            // surface is left bare. A row this slice does not mount has no
            // operation to carry them and is not counted (Resolution 7).
            val bare =
                ApiContract.endpoints
                    .mapNotNull { row ->
                        val verb = row.method.wireValue
                        val operation = paths[row.path]?.jsonObject?.get(verb.lowercase())?.jsonObject
                        "$verb ${row.path}".takeIf { operation != null && operation["responses"] == null }
                    }
            assertEquals(emptyList(), bare)
        }

    private fun testPoiService(ctx: DSLContext): PoiService =
        PoiService(
            poiRepo = PoiServingRepo(ctx, enabledDataProviders = emptySet()),
            detailServices =
                listOf(
                    testCampgroundService(ctx),
                    TeslaSuperchargerService(TeslaSuperchargerRepo(ctx)),
                    PlanetFitnessLocationService(PlanetFitnessLocationRepo(ctx)),
                ),
        )

    /**
     * Mounted for its *shape*: the document is built from the routing tree, and
     * these routes never answer here. A detached DSLContext is enough, the same
     * way `RouteCorridorRepo` is built above.
     */
    private fun testDashboardController(ctx: DSLContext): AvailabilityDashboardController =
        AvailabilityDashboardController(
            pollerRepo = AvailabilityPollerRepo(ctx),
            runRepo = AvailabilityRunRepo(ctx),
            availabilityRepo = AvailabilityRepo(ctx),
            campsiteRepo = CampsiteRepo(ctx),
            forcePullCooldown = Duration.ofSeconds(FORCE_POLLER_COOLDOWN_SECONDS),
        )

    private fun testRouteConfig(): RouteConfig =
        RouteConfig(
            maxWaypoints = 25,
            minCorridorRadiusMiles = 1.0,
            maxCorridorRadiusMiles = 100.0,
        )
}
