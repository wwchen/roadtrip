package ca.floo.roadtrip.route.api.geocode

import ca.floo.roadtrip.client.mapbox.MapboxGeocoder
import ca.floo.roadtrip.model.api.GeocodeResponseDto
import ca.floo.roadtrip.model.api.GeocodeResultDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.routing.GeocodeResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.boundedIntQuery
import ca.floo.roadtrip.route.common.matchingQuery
import ca.floo.roadtrip.route.common.queryParam
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.trimmedQuery
import ca.floo.roadtrip.support.GeocodeException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val lngLatRegex = Regex("""^-?\d{1,3}(\.\d{1,8})?,-?\d{1,3}(\.\d{1,8})?$""")
private const val DEFAULT_GEOCODE_LIMIT = 5
private const val MIN_GEOCODE_LIMIT = 1
private const val MAX_GEOCODE_LIMIT = 10

private val geocodeLimitRange = MIN_GEOCODE_LIMIT..MAX_GEOCODE_LIMIT

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
    route("/api") {
        get("/geocode") {
            if (!geocoder.configured) {
                call.respondGeocodeError(
                    "geocoding_unavailable",
                    HttpStatusCode.ServiceUnavailable,
                    detail = "roadtrip.mapbox.token not set",
                )
                return@get
            }

            val q = call.trimmedQuery("q")
            if (q.isBlank() || q.length > 200) {
                call.respondGeocodeError("bad_query", HttpStatusCode.BadRequest)
                return@get
            }

            val autocomplete = call.queryParam("autocomplete") != "0"
            val limit = call.boundedIntQuery("limit", DEFAULT_GEOCODE_LIMIT, geocodeLimitRange)
            val proximity = call.matchingQuery("proximity", lngLatRegex)

            val results =
                try {
                    geocoder.forward(q, autocomplete = autocomplete, proximity = proximity, limit = limit)
                } catch (e: GeocodeException) {
                    call.respondGeocodeError("geocoding_unavailable", HttpStatusCode.ServiceUnavailable)
                    return@get
                }

            call.respondGeocodeJson(geocodeResponseDto(results))
        }.access(RouteAccess.Anonymous)
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
    respondApiError(error = error, status = status, detail = detail)
}

private suspend inline fun <reified T> ApplicationCall.respondGeocodeJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondEncodedJson(geocodeRouteJson, value, status)
}
