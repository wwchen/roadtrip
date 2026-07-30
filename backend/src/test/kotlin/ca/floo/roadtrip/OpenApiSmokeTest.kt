package ca.floo.roadtrip

import ca.floo.roadtrip.client.mapbox.MapboxDirections
import ca.floo.roadtrip.config.RouteConfig
import ca.floo.roadtrip.repo.CampgroundRepo
import ca.floo.roadtrip.repo.PlanetFitnessLocationRepo
import ca.floo.roadtrip.repo.PoiServingRepo
import ca.floo.roadtrip.repo.RouteCorridorRepo
import ca.floo.roadtrip.repo.TeslaSuperchargerRepo
import ca.floo.roadtrip.route.api.docs.apiDocsRoutes
import ca.floo.roadtrip.route.api.health.healthRoutes
import ca.floo.roadtrip.route.api.pois.poiRoutes
import ca.floo.roadtrip.route.api.pois.poisOnRouteRoutes
import ca.floo.roadtrip.service.health.ReadinessService
import ca.floo.roadtrip.service.poi.CampgroundService
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
                    get("/web/{path...}") { call.respondText("static") }
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
            assertFalse(paths.containsKey("/api/docs"))
            assertFalse(paths.containsKey("/api/docs/openapi.json"))
            assertFalse(paths.containsKey("/"))
            assertFalse(paths.keys.any { it.startsWith("/web") })
            assertFalse(paths.containsKey("/api/campsite/events"))
            assertFalse(paths.containsKey("/api/campsite/availability/{poi_id}"))
            assertFalse(paths.containsKey("/api/poi/{poi_id}/reservables/availability"))
            assertFalse(paths.containsKey("/api/admin/campsite/debug/synth-match"))
        }

    private fun testPoiService(ctx: DSLContext): PoiService =
        PoiService(
            poiRepo = PoiServingRepo(ctx, enabledDataProviders = emptySet()),
            detailServices =
                listOf(
                    CampgroundService(
                        campgroundRepo = CampgroundRepo(ctx),
                        dateResolver =
                            ca.floo.roadtrip.service.availability
                                .AvailabilityDateResolver(ctx),
                    ),
                    TeslaSuperchargerService(TeslaSuperchargerRepo(ctx)),
                    PlanetFitnessLocationService(PlanetFitnessLocationRepo(ctx)),
                ),
        )

    private fun testRouteConfig(): RouteConfig =
        RouteConfig(
            maxWaypoints = 25,
            minCorridorRadiusMiles = 1.0,
            maxCorridorRadiusMiles = 100.0,
        )
}
