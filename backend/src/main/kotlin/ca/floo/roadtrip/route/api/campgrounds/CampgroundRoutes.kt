package ca.floo.roadtrip.route.api.campgrounds

import ca.floo.roadtrip.config.CampgroundSearchConfig
import ca.floo.roadtrip.model.api.campground.CampgroundDetailsRequestDto
import ca.floo.roadtrip.model.api.campground.CampgroundSearchRequestDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.poi.CampgroundSearchRequestException
import ca.floo.roadtrip.service.poi.CampgroundSearchService
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val BAD_REQUEST_ERROR = "bad_request"

// POST /api/campgrounds/search and POST /api/campgrounds/details.
//
// The campground list's pair: which campground POIs inside a boundary pass a
// filter, then bulk summaries for the ids the list will render. POST /api/pois
// stays the pin layer for every category.
internal fun Route.campgroundRoutes(
    service: CampgroundSearchService,
    config: CampgroundSearchConfig,
) {
    route("/api") {
        route("/campgrounds") {
            post("/search") {
                val request =
                    when (val body = call.receiveJsonBody<CampgroundSearchRequestDto>()) {
                        is RouteBodyResult.Invalid -> return@post call.respondBadRequest(body.detail)
                        is RouteBodyResult.Valid -> body.value
                    }
                val response =
                    try {
                        service.search(request)
                    } catch (e: CampgroundSearchRequestException) {
                        return@post call.respondApiError(e.code, HttpStatusCode.BadRequest, e.message)
                    }
                call.respondEncodedJson(response)
            }.describeApi(
                tag = "campground",
                summary = "Campground POI ids inside a GeoJSON boundary that pass a filter",
                description =
                    "Body: { boundary: GeoJSON Polygon|MultiPolygon, filter?: { site_type?, group_size?, amenities? } }. " +
                        "Ids are pois.id, nearest the boundary's centre first, at most ${config.maxResults} " +
                        "(truncated:true past that). A campground with no data for a filter field passes it.",
            ).access(RouteAccess.Anonymous)

            post("/details") {
                val request =
                    when (val body = call.receiveJsonBody<CampgroundDetailsRequestDto>()) {
                        is RouteBodyResult.Invalid -> return@post call.respondBadRequest(body.detail)
                        is RouteBodyResult.Valid -> body.value
                    }
                val response =
                    try {
                        service.details(request)
                    } catch (e: CampgroundSearchRequestException) {
                        return@post call.respondApiError(e.code, HttpStatusCode.BadRequest, e.message)
                    }
                call.respondEncodedJson(response)
            }.describeApi(
                tag = "campground",
                summary = "Bulk campground summaries by POI id",
                description =
                    "Body: { campground_ids: [pois.id, ...1..${config.maxDetailIds}] }. One summary per known id in " +
                        "request order; unknown ids are omitted. Carries the fields the in-view list and its filters read.",
            ).access(RouteAccess.Anonymous)
        }
    }
}

private suspend fun ApplicationCall.respondBadRequest(detail: String?) =
    respondApiError(BAD_REQUEST_ERROR, HttpStatusCode.BadRequest, detail ?: "parse failed")
