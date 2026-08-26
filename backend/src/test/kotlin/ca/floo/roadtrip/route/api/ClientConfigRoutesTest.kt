package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.config.CartoBasemapsConfig
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
import kotlin.test.assertFalse

class ClientConfigRoutesTest {
    @Test
    fun `GET client-config exposes the Carto basemaps key when configured`() =
        testApplication {
            application {
                routing {
                    clientConfigRoutes(CartoBasemapsConfig(apiKey = "carto-test-key"))
                }
            }

            val response = client.get("/api/client-config")

            assertEquals(HttpStatusCode.OK, response.status)
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("carto-test-key", obj["carto_basemaps_api_key"]!!.jsonPrimitive.content)
        }

    @Test
    fun `GET client-config omits the Carto key when unset`() =
        testApplication {
            application {
                routing {
                    clientConfigRoutes(CartoBasemapsConfig(apiKey = null))
                }
            }

            val response = client.get("/api/client-config")

            assertEquals(HttpStatusCode.OK, response.status)
            val obj = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertFalse("carto_basemaps_api_key" in obj)
        }
}
