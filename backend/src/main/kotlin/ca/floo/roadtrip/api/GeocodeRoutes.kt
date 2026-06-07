package ca.floo.roadtrip.api

import ca.floo.roadtrip.geocode.GeocodeException
import ca.floo.roadtrip.geocode.MapboxGeocoder
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val LNGLAT_RE = Regex("""^-?\d{1,3}(\.\d{1,8})?,-?\d{1,3}(\.\d{1,8})?$""")

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
            call.respondText(
                """{"error":"geocoding_unavailable","detail":"MAPBOX_TOKEN not set"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val q =
            call.request.queryParameters["q"]
                ?.trim()
                .orEmpty()
        if (q.isBlank() || q.length > 200) {
            call.respondText(
                """{"error":"bad_query"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
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
                call.respondText(
                    """{"error":"geocoding_unavailable","retry_after_s":30}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@get
            }

        val json =
            buildJsonObject {
                put(
                    "results",
                    buildJsonArray {
                        for (r in results) {
                            add(
                                buildJsonObject {
                                    put("id", r.id)
                                    put("place_name", r.placeName)
                                    put("place_type", r.placeType)
                                    put("lng", r.lng)
                                    put("lat", r.lat)
                                },
                            )
                        }
                    },
                )
            }
        call.respondText(json.toString(), io.ktor.http.ContentType.Application.Json)
    }
}
