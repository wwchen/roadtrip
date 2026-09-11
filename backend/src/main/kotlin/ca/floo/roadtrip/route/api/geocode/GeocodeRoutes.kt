package ca.floo.roadtrip.route.api.geocode

import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.queryParam
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.trimmedQuery
import ca.floo.roadtrip.service.geocode.GeocodeOutcome
import ca.floo.roadtrip.service.geocode.GeocodeService
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

private const val AUTOCOMPLETE_OFF = "0"

/**
 * GET /api/geocode?q=<text>[&autocomplete=0][&proximity=lng,lat][&limit=N]
 *
 * Backend proxy for Mapbox forward-geocoding. The frontend's top-bar search
 * debounces input then hits this endpoint for autofill suggestions.
 *
 * Response shape (also documented for swagger):
 *   { "results": [ { id, place_name, place_type, lng, lat, bbox? }, ... ] }
 *
 * `bbox` is `[west, south, east, north]` and is present only for a feature the
 * upstream reports an extent for — a country, a region, a district, a place, a
 * park with a footprint. It is what lets the client frame a searched-for REGION
 * as an area instead of flying to an arbitrary point inside it.
 */
internal fun Route.geocodeRoutes(geocodeService: GeocodeService) {
    route("/api") {
        get("/geocode") {
            val outcome =
                geocodeService.geocode(
                    query = call.trimmedQuery("q"),
                    autocomplete = call.queryParam("autocomplete") != AUTOCOMPLETE_OFF,
                    proximity = call.queryParam("proximity"),
                    limit = call.queryParam("limit")?.toIntOrNull(),
                )

            when (outcome) {
                GeocodeOutcome.NotConfigured ->
                    call.respondApiError(
                        "geocoding_unavailable",
                        HttpStatusCode.ServiceUnavailable,
                        detail = "roadtrip.mapbox.token not set",
                    )
                GeocodeOutcome.BadQuery -> call.respondApiError("bad_query", HttpStatusCode.BadRequest)
                GeocodeOutcome.Unavailable -> call.respondApiError("geocoding_unavailable", HttpStatusCode.ServiceUnavailable)
                is GeocodeOutcome.Found -> call.respondEncodedJson(outcome.response)
            }
        }.access(RouteAccess.Anonymous)
    }
}
