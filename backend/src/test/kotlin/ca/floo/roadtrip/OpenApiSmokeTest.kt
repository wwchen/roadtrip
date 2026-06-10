package ca.floo.roadtrip

import ca.floo.campsite.recgov.booker.api.eventsRoutes
import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.campsite.recgov.booker.events.EventBus
import ca.floo.campsite.recgov.booker.poller.AvailabilityClient
import ca.floo.roadtrip.client.AspiraAvailabilityClient
import ca.floo.roadtrip.client.MapboxDirections
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.repo.RouteCache
import ca.floo.roadtrip.routes.campsiteAvailabilityRoutes
import ca.floo.roadtrip.routes.healthRoutes
import ca.floo.roadtrip.routes.poiRoutes
import ca.floo.roadtrip.routes.poisOnRouteRoutes
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
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// Smoke for /api/docs (issue #47).
//
// Boots a slim test app with the SwaggerUI plugin + the same /api/docs and
// /api/docs/openapi.json routes Main.kt mounts. Asserts:
//   - GET /api/docs returns 200 (Swagger UI HTML).
//   - GET /api/docs/openapi.json returns 200 with a parseable spec listing
//     the documented paths and their summaries.
//
// We don't boot the full Application.module() here because that would pull
// in Postgres, Flyway, the campsite event bus, etc. — overkill when we
// just want to verify the plugin wires correctly.
class OpenApiSmokeTest {
    @Test
    fun `swagger UI serves at api docs`() =
        testApplication {
            application {
                install(SwaggerUI)
                routing {
                    route("/api/docs") { swaggerUI("/api/docs/openapi.json") }
                    route("/api/docs/openapi.json") { openApiSpec() }
                }
            }
            val resp = client.get("/api/docs")
            assertEquals(HttpStatusCode.OK, resp.status)
            val body = resp.bodyAsText()
            // Swagger UI's loader page references swagger-initializer.js.
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
                install(SwaggerUI)
                install(SSE)
                val ctx = DSL.using(SQLDialect.POSTGRES)
                val registry = PoiRegistry.load(File("../config/poi-registry.yaml"))
                routing {
                    route("/api/docs/openapi.json") { openApiSpec() }
                    healthRoutes()
                    poiRoutes(ctx, registry)
                    poisOnRouteRoutes(ctx, RouteCache(MapboxDirections(token = null)), registry)
                    campsiteAvailabilityRoutes(
                        ctx,
                        CachedAvailability(AvailabilityClient()),
                        CachedAspiraAvailability(AspiraAvailabilityClient()),
                        registry,
                    )
                    eventsRoutes(EventBus())
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

            val bulkPost =
                paths["/api/campsite/availability/bulk"]!!.jsonObject["post"]!!.jsonObject
            assertEquals(
                "Bulk per-day availability for many campgrounds in a date window (poi-id keyed)",
                bulkPost["summary"]!!.jsonPrimitive.content,
            )

            val campsiteEventsGet =
                paths["/api/campsite/events"]!!.jsonObject["get"]!!.jsonObject
            assertEquals(
                "Live campsite event stream (SSE)",
                campsiteEventsGet["summary"]!!.jsonPrimitive.content,
            )
            assertEquals(
                "campsite",
                campsiteEventsGet["tags"]!!
                    .jsonArray
                    .single()
                    .jsonPrimitive
                    .content,
            )
        }

    @Test
    fun `response examples land in the openapi spec`() =
        testApplication {
            application {
                install(SwaggerUI)
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
