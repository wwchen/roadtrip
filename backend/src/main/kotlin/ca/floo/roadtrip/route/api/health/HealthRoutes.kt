package ca.floo.roadtrip.route.api.health

import ca.floo.roadtrip.model.api.HealthResponseDto
import ca.floo.roadtrip.route.common.describeApi
import io.ktor.http.ContentType
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
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
    route("/api") {
        get("/health") {
            call.respondHealthJson(healthResponseDto(Instant.now().epochSecond))
        }.describeApi("health", "Application liveness/readiness probe")
    }
}

internal fun healthResponseDto(now: Long): HealthResponseDto = HealthResponseDto(now = now)

internal inline fun <reified T> encodeHealthJson(value: T): String = healthRouteJson.encodeToString(value)

private suspend inline fun <reified T> ApplicationCall.respondHealthJson(value: T) {
    respondText(encodeHealthJson(value), ContentType.Application.Json)
}
