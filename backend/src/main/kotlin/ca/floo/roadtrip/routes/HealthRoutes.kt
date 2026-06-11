package ca.floo.roadtrip.routes

import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant

@OptIn(ExperimentalSerializationApi::class)
private val healthRouteJson =
    Json {
        encodeDefaults = true
    }

// Infra liveness/readiness JSON. Keep this endpoint boring: probes should only
// need to know that the Ktor app booted and can answer requests.
fun Route.healthRoutes() {
    get("/api/health", {
        tags = listOf("health")
        summary = "Application liveness/readiness probe"
        response {
            code(HttpStatusCode.OK) {
                body<HealthResponseDto> {
                    mediaTypes(ContentType.Application.Json)
                    example("ok") {
                        value = HealthResponseDto(now = 1717683240)
                    }
                }
            }
        }
    }) {
        call.respondHealthJson(healthResponseDto(Instant.now().epochSecond))
    }
}

internal fun healthResponseDto(now: Long): HealthResponseDto = HealthResponseDto(now = now)

internal inline fun <reified T> encodeHealthJson(value: T): String = healthRouteJson.encodeToString(value)

@Serializable
internal data class HealthResponseDto(
    val status: String = "ok",
    val now: Long,
)

private suspend inline fun <reified T> ApplicationCall.respondHealthJson(value: T) {
    respondText(encodeHealthJson(value), ContentType.Application.Json)
}
