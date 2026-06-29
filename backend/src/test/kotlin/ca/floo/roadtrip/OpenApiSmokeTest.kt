package ca.floo.roadtrip

import ca.floo.roadtrip.clients.aspira.HttpAspiraAvailabilityClient
import ca.floo.roadtrip.clients.cache.RouteCache
import ca.floo.roadtrip.clients.mapbox.MapboxDirections
import ca.floo.roadtrip.clients.recgov.HttpAvailabilityClient
import ca.floo.roadtrip.models.metadata.registry.PoiRegistry
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.routes.availabilityRoutes
import ca.floo.roadtrip.routes.healthRoutes
import ca.floo.roadtrip.routes.poiRoutes
import ca.floo.roadtrip.routes.poisOnRouteRoutes
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistryFactory
import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.routing.openApiSpec
import io.github.smiley4.ktorswaggerui.routing.swaggerUI
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import io.ktor.server.routing.get as ktorGet

// Smoke for /api/docs (issue #47).
//
// Boots a slim test app with the SwaggerUI plugin + the same /api/docs and
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
                install(SwaggerUI) {
                    pathFilter = { _, path -> includeInRoadtripOpenApi(path) }
                }
                routing {
                    route("/api/docs") { swaggerUI("/api/docs/openapi.json") }
                    route("/api/docs/openapi.json") { openApiSpec() }
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
                install(SwaggerUI) {
                    pathFilter = { _, path -> includeInRoadtripOpenApi(path) }
                }
                val ctx = DSL.using(SQLDialect.POSTGRES)
                val registry = PoiRegistry.load(File("../config/poi-registry.yaml"))
                routing {
                    route("/api/docs/openapi.json") { openApiSpec() }
                    ktorGet("/") { call.respondText("root") }
                    ktorGet("/web/{path...}") { call.respondText("static") }
                    healthRoutes()
                    poiRoutes(ctx, registry)
                    poisOnRouteRoutes(ctx, RouteCache(MapboxDirections(token = null)), registry)
                    val reservationProviders =
                        ReservationProviderRegistryFactory.build(
                            registry = registry,
                            recgovClient = HttpAvailabilityClient(),
                            aspiraClient = HttpAspiraAvailabilityClient(),
                        )
                    availabilityRoutes(CampsiteProviderRepo(ctx), reservationProviders, ReservableRepo(ctx))
                }
            }

            val resp = client.get("/api/docs/openapi.json")
            assertEquals(HttpStatusCode.OK, resp.status)

            val spec = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertNotNull(spec["openapi"], "spec missing openapi version")
            val paths = spec["paths"]!!.jsonObject

            val healthGet =
                paths["/api/health"]!!.jsonObject["get"]!!.jsonObject
            assertEquals("Application liveness/readiness probe", healthGet["summary"]!!.jsonPrimitive.content)
            assertEquals(
                "health",
                healthGet["tags"]!!
                    .toString()
                    .removePrefix("[")
                    .removeSuffix("]")
                    .trim('"'),
            )

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
            assertFalse(paths.containsKey("/"))
            assertFalse(paths.keys.any { it.startsWith("/web") })
            assertFalse(paths.containsKey("/api/campsite/events"))
            assertFalse(paths.containsKey("/api/campsite/availability/{poi_id}"))
            assertFalse(paths.containsKey("/api/admin/campsite/debug/synth-match"))
        }

    @Test
    fun `response examples land in the openapi spec`() =
        testApplication {
            application {
                install(SwaggerUI) {
                    pathFilter = { _, path -> includeInRoadtripOpenApi(path) }
                }
                routing {
                    route("/api/docs/openapi.json") { openApiSpec() }
                    get("/api/example", {
                        tags = listOf("test")
                        summary = "Has examples"
                        response {
                            code(HttpStatusCode.OK) {
                                body<String> {
                                    mediaTypes(io.ktor.http.ContentType.Application.Json)
                                    example("happy") { value = """{"hello":"world"}""" }
                                }
                            }
                        }
                    }) { call.respondText("ok") }
                }
            }

            val resp = client.get("/api/docs/openapi.json")
            assertEquals(HttpStatusCode.OK, resp.status)

            val spec = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            // paths./api/example.get.responses.200.content."application/json".examples.happy.value
            val examples =
                spec["paths"]!!
                    .jsonObject["/api/example"]!!
                    .jsonObject["get"]!!
                    .jsonObject["responses"]!!
                    .jsonObject["200"]!!
                    .jsonObject["content"]!!
                    .jsonObject["application/json"]!!
                    .jsonObject["examples"]!!
                    .jsonObject

            assertNotNull(examples["happy"], "named example 'happy' not found in spec")
            // The example value is reflected verbatim. The plugin may serialize
            // the JSON string with escapes or as a literal — accept either, just
            // confirm the inner payload is round-tripped.
            assertTrue(
                examples["happy"]!!.toString().contains("hello"),
                "example payload missing from spec; got: ${examples["happy"]}",
            )
        }
}
