package ca.floo.roadtrip.api

import io.github.smiley4.ktorswaggerui.SwaggerUI
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
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
    fun `openapi spec lists annotated paths with summaries and tags`() =
        testApplication {
            application {
                install(SwaggerUI)
                routing {
                    route("/api/docs/openapi.json") { openApiSpec() }
                    get("/api/health", {
                        tags = listOf("health")
                        summary = "Liveness probe"
                    }) { call.respondText("ok") }
                    post("/api/admin/data/fetch", {
                        tags = listOf("admin")
                        summary = "Fetch upstream data"
                    }) { call.respondText("ok") }
                }
            }

            val resp = client.get("/api/docs/openapi.json")
            assertEquals(HttpStatusCode.OK, resp.status)

            val spec = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertNotNull(spec["openapi"], "spec missing openapi version")
            val paths = spec["paths"]!!.jsonObject

            // /api/health under "get"
            val healthGet =
                paths["/api/health"]!!.jsonObject["get"]!!.jsonObject
            assertEquals("Liveness probe", healthGet["summary"]!!.jsonPrimitive.content)
            assertEquals(
                "health",
                healthGet["tags"]!!
                    .toString()
                    .removePrefix("[")
                    .removeSuffix("]")
                    .trim('"'),
            )

            // /api/admin/data/fetch under "post"
            val fetchPost =
                paths["/api/admin/data/fetch"]!!.jsonObject["post"]!!.jsonObject
            assertEquals("Fetch upstream data", fetchPost["summary"]!!.jsonPrimitive.content)
        }
}
