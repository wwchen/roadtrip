package ca.floo.roadtrip.route

import ca.floo.roadtrip.route.api.health.encodeHealthJson
import ca.floo.roadtrip.route.api.health.healthResponseDto
import ca.floo.roadtrip.route.api.health.healthRoutes
import ca.floo.roadtrip.service.health.ReadinessService
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals

class HealthRoutesTest {
    @Test
    fun `health response serializes status and epoch seconds with dto`() {
        val json = Json.parseToJsonElement(encodeHealthJson(healthResponseDto(1717683240))).jsonObject

        assertEquals("ok", json["status"]!!.jsonPrimitive.content)
        assertEquals(1717683240, json["now"]!!.jsonPrimitive.long)
    }

    @Test
    fun `liveness stays up even when readiness reports the database down`() =
        testApplication {
            application { routing { healthRoutes(readiness(databaseReachable = false)) } }

            val response = client.get("/api/health")

            assertEquals(HttpStatusCode.OK, response.status, "liveness must not depend on Postgres")
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("ok", body["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `readiness returns 200 ready when the database probe succeeds`() =
        testApplication {
            application { routing { healthRoutes(readiness(databaseReachable = true)) } }

            val response = client.get("/api/health/ready")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("ready", body["status"]!!.jsonPrimitive.content)
            assertEquals("up", body["database"]!!.jsonPrimitive.content)
        }

    @Test
    fun `readiness returns 503 not_ready when the database probe fails`() =
        testApplication {
            application { routing { healthRoutes(readiness(databaseReachable = false)) } }

            val response = client.get("/api/health/ready")

            assertEquals(
                HttpStatusCode.ServiceUnavailable,
                response.status,
                "a deploy gate reading only the status line must see the failure",
            )
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("not_ready", body["status"]!!.jsonPrimitive.content)
            assertEquals("down", body["database"]!!.jsonPrimitive.content)
        }

    private fun readiness(databaseReachable: Boolean) = ReadinessService { ReadinessService.Report(databaseReachable = databaseReachable) }
}
