package ca.floo.roadtrip.route.common

import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.route.routeTestApplication
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.call
import io.ktor.server.routing.post
import io.ktor.server.testing.testApplication
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalSerializationApi::class)
private val requestBodyTestJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

class RouteRequestBodiesTest {
    @Test
    fun `receiveJsonBody returns invalid for malformed typed json`() =
        testApplication {
            application {
                routeTestApplication {
                    post("/api/body") {
                        when (val body = call.receiveJsonBody<RequestBodyTestDto>()) {
                            is RouteBodyResult.Invalid ->
                                call.respondEncodedJson(
                                    ApiErrorSchema(error = "invalid_body", detail = body.detail),
                                    HttpStatusCode.BadRequest,
                                )
                            is RouteBodyResult.Valid -> call.respondEncodedJson(body.value)
                        }
                    }
                }
            }

            val response =
                client.post("/api/body") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":""")
                }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("invalid_body", body["error"]!!.jsonPrimitive.content)
        }

    @Test
    fun `decodeOptionalTextJsonBody uses default for blank bodies`() =
        testApplication {
            application {
                routeTestApplication {
                    post("/api/optional-body") {
                        when (
                            val body =
                                call.decodeOptionalTextJsonBody(
                                    json = requestBodyTestJson,
                                    default = ::RequestBodyTestDto,
                                )
                        ) {
                            is RouteBodyResult.Invalid ->
                                call.respondEncodedJson(
                                    ApiErrorSchema(error = "invalid_body", detail = body.detail),
                                    HttpStatusCode.BadRequest,
                                )
                            is RouteBodyResult.Valid -> call.respondEncodedJson(body.value)
                        }
                    }
                }
            }

            val response =
                client.post("/api/optional-body") {
                    contentType(ContentType.Application.Json)
                    setBody("")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
            assertEquals("default", body["name"]!!.jsonPrimitive.content)
        }

    @Test
    fun `mapCatching turns validation exceptions into invalid bodies`() {
        val result =
            RouteBodyResult
                .Valid(RequestBodyTestDto(name = ""))
                .mapCatching { dto ->
                    require(dto.name.isNotBlank()) { "name is required" }
                    dto
                }

        val invalid = result as RouteBodyResult.Invalid
        assertEquals("name is required", invalid.detail)
    }
}

@Serializable
private data class RequestBodyTestDto(
    val name: String = "default",
)
