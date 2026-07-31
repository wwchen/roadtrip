package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.config.BuildInfoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoRoutesTest {
    @Test
    fun `GET build-info returns env sha branch`() =
        testApplication {
            application {
                routing {
                    buildInfoRoutes(BuildInfoConfig(env = "sandbox", sha = "abc1234", branch = "fix-foo"))
                }
            }
            val resp = client.get("/api/build-info")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("sandbox", obj["env"]!!.jsonPrimitive.content)
            assertEquals("abc1234", obj["sha"]!!.jsonPrimitive.content)
            assertEquals("fix-foo", obj["branch"]!!.jsonPrimitive.content)
        }
}
