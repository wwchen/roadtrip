package ca.floo.roadtrip.routes

import ca.floo.roadtrip.clients.mapbox.MapboxGeocoder
import ca.floo.roadtrip.exceptions.GeocodeException
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.GeocodeResponseDto
import ca.floo.roadtrip.models.api.GeocodeResultDto
import ca.floo.roadtrip.models.routing.GeocodeResult
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val LNGLAT_RE = Regex("""^-?\d{1,3}(\.\d{1,8})?,-?\d{1,3}(\.\d{1,8})?$""")

@OptIn(ExperimentalSerializationApi::class)
private val geocodeRouteJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * GET /api/geocode?q=<text>[&autocomplete=0][&proximity=lng,lat][&limit=N]
 *
 * Backend proxy for Mapbox forward-geocoding. The frontend's top-bar search
 * debounces input then hits this endpoint for autofill suggestions.
 *
 * Response shape (also documented for swagger):
 *   { "results": [ { id, place_name, place_type, lng, lat }, ... ] }
 */
fun Route.geocodeRoutes(geocoder: MapboxGeocoder) {
    get("/api/geocode") {
        if (!geocoder.configured) {
            call.respondGeocodeError(
                "geocoding_unavailable",
                HttpStatusCode.ServiceUnavailable,
                detail = "roadtrip.mapbox.token not set",
            )
            return@get
        }

        val q =
            call.request.queryParameters["q"]
                ?.trim()
                .orEmpty()
        if (q.isBlank() || q.length > 200) {
            call.respondGeocodeError("bad_query", HttpStatusCode.BadRequest)
            return@get
        }

        val autocomplete = call.request.queryParameters["autocomplete"] != "0"
        val limit =
            call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, 10) ?: 5
        val proximity = call.request.queryParameters["proximity"]?.takeIf { LNGLAT_RE.matches(it) }

        val results =
            try {
                geocoder.forward(q, autocomplete = autocomplete, proximity = proximity, limit = limit)
            } catch (e: GeocodeException) {
                call.respondGeocodeError("geocoding_unavailable", HttpStatusCode.ServiceUnavailable)
                return@get
            }

        call.respondGeocodeJson(geocodeResponseDto(results))
    }
}

internal fun geocodeResponseDto(results: List<GeocodeResult>): GeocodeResponseDto =
    GeocodeResponseDto(
        results =
            results.map { result ->
                GeocodeResultDto(
                    id = result.id,
                    placeName = result.placeName,
                    placeType = result.placeType,
                    lng = result.lng,
                    lat = result.lat,
                )
            },
    )

internal inline fun <reified T> encodeGeocodeJson(value: T): String = geocodeRouteJson.encodeToString(value)

private suspend fun ApplicationCall.respondGeocodeError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondGeocodeJson(ApiErrorSchema(error = error, detail = detail), status)
}

private suspend inline fun <reified T> ApplicationCall.respondGeocodeJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(encodeGeocodeJson(value), ContentType.Application.Json, status)
}
