package ca.floo.roadtrip

import ca.floo.roadtrip.model.api.ApiErrorSchema
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.plugins.MissingRequestParameterException
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class RoadtripRoutingTest {
    @Test
    fun `status pages render bad request exceptions as api error json`() =
        testApplication {
            application {
                installRoadtripPlugins()
                routing {
                    get("/api/needs-param") {
                        throw MissingRequestParameterException("poi_id", "query parameter")
                    }
                }
            }

            val response = client.get("/api/needs-param")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("missing_parameter", body["error"]!!.jsonPrimitive.content)
            assertEquals("poi_id is required", body["detail"]!!.jsonPrimitive.content)
        }

    @Test
    fun `content negotiation serializes typed api responses`() =
        testApplication {
            application {
                installRoadtripPlugins()
                routing {
                    get("/api/typed") {
                        call.respond(ApiErrorSchema(error = "ok"))
                    }
                }
            }

            val response = client.get("/api/typed")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("ok", body["error"]!!.jsonPrimitive.content)
            assertEquals(null, body["detail"])
        }
}
