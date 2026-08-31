package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.model.api.poi.PoiDetailFeatureSchema
import ca.floo.roadtrip.model.api.poi.PoiFeatureCollectionSchema
import ca.floo.roadtrip.model.api.poi.PoisRequestSchema
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.boundedIntQuery
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.longPath
import ca.floo.roadtrip.route.common.mapCatching
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.splitQueryValues
import ca.floo.roadtrip.route.common.trimmedQuery
import ca.floo.roadtrip.service.poi.CampgroundService
import ca.floo.roadtrip.service.poi.POI_LIMIT
import ca.floo.roadtrip.service.poi.PoiReader
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val DEFAULT_SEARCH_LIMIT = 10
private const val MIN_SEARCH_LIMIT = 1
private const val MAX_SEARCH_LIMIT = 25

private val searchLimitRange = MIN_SEARCH_LIMIT..MAX_SEARCH_LIMIT

// POST /api/pois
//
// Returns a GeoJSON FeatureCollection of POIs in the requested bbox.
// One round-trip per pan; the FE debounces moveend by 250ms.
//
// Categories are picked by the FE; default (when omitted) is the canonical
// serving set owned by PoiService. Each requested category gets its own slot
// budget out of POI_LIMIT so a dense layer cannot starve sparser ones;
// truncated:true tells the client to ask the user to zoom in further.
//
// Corridor filtering moved to POST /api/pois/on-route. The trip planner's
// "campgrounds along route" list needs the full set, not a viewport slice +
// per-category sample.
internal fun Route.poiRoutes(poiService: PoiReader) {
    route("/api") {
        route("/pois") {
            post {
                val req =
                    when (
                        val body =
                            call
                                .receiveJsonBody<PoisRequestSchema>()
                                .mapCatching(::parseRequest)
                    ) {
                        is RouteBodyResult.Invalid -> {
                            call.respondPoiError(
                                "bad_request",
                                HttpStatusCode.BadRequest,
                                body.detail ?: "parse failed",
                            )
                            return@post
                        }
                        is RouteBodyResult.Valid -> body.value
                    }

                call.respondPoiFeatureJson(
                    poiService.pois(
                        bbox = req.bbox,
                        zoom = req.zoom,
                        categories = req.categories,
                    ),
                )
            }.describeApi(
                tag = "poi",
                summary = "POIs within bbox; capped at $POI_LIMIT features (truncated:true on overflow)",
                description =
                    "Body: { bbox: [w,s,e,n], zoom?, categories? }. " +
                        "categories defaults to the canonical serving set. " +
                        "zoom < ${CampgroundService.MIN_POI_ZOOM} suppresses campgrounds even when requested. " +
                        "Corridor filtering has moved to POST /api/pois/on-route.",
            ).access(RouteAccess.Anonymous)

            // GET /api/pois/search?q=...&limit=10
            //
            // Text-search across the full POI index by name. Used by the topbar
            // dropdown so a user can find a POI without panning to it first.
            get("/search") {
                val q = call.trimmedQuery("q")
                val limit = call.boundedIntQuery("limit", DEFAULT_SEARCH_LIMIT, searchLimitRange)
                val categories = call.splitQueryValues("categories")
                call.respondPoiJson(
                    poiService.search(
                        query = q,
                        categories = categories,
                        limit = limit,
                    ),
                )
            }.describeApi(
                tag = "poi",
                summary = "Text search POIs by name (cross-viewport)",
                description =
                    "Returns up to `limit` matches ranked by prefix-match -> name length -> alphabetical. " +
                        "Empty `q` (or shorter than 2 chars) returns an empty list. " +
                        "`categories` optionally filters to one or more comma-separated POI categories. " +
                        "Used by the topbar dropdown so a user can find a POI nationwide without panning to it first.",
            ).access(RouteAccess.Anonymous)

            // GET /api/pois/{id}
            //
            // Per-row detail. The bbox endpoint ships only id + lat/lng + category +
            // subcategory + agency; this endpoint backs the popup/drawer "I clicked a
            // pin" flow with the full feature shape.
            get("/{id}") {
                val id =
                    call.longPath("id")
                        ?: return@get call.respondPoiError("bad_id", HttpStatusCode.BadRequest)
                val feature =
                    poiService.poiDetail(id)
                        ?: return@get call.respondPoiError("not_found", HttpStatusCode.NotFound)
                call.response.headers.append(
                    "Cache-Control",
                    "public, max-age=300, stale-while-revalidate=3600",
                )
                call.respondPoiFeatureJson(feature)
            }.describeApi(
                tag = "poi",
                summary = "Full per-row POI detail (the slim bbox endpoint omits these fields)",
                description =
                    "Returns one GeoJSON Feature with the wide property set. " +
                        "Cacheable: max-age=300, stale-while-revalidate=3600.",
            ).access(RouteAccess.Anonymous)
        }
    }
}

private data class PoiRequest(
    val bbox: Bbox,
    val zoom: Int?,
    val categories: List<String>?,
)

private fun parseRequest(dto: PoisRequestSchema): PoiRequest {
    val nums = dto.bbox
    require(nums.size == 4) { "bbox must be [west,south,east,north]" }
    val (w, s, e, n) = nums
    require(w in -180.0..180.0 && e in -180.0..180.0) { "bbox lng out of range" }
    require(s in -90.0..90.0 && n in -90.0..90.0) { "bbox lat out of range" }
    require(w < e && s < n) { "bbox: west must be < east, south < north" }
    val bbox = Bbox(w, s, e, n)

    val categories =
        dto.categories
            ?.mapNotNull { it.trim().takeIf { category -> category.isNotEmpty() } }
            ?.takeIf { it.isNotEmpty() }

    return PoiRequest(bbox, dto.zoom, categories)
}

private suspend fun ApplicationCall.respondPoiFeatureJson(value: PoiFeatureCollectionSchema) {
    respondEncodedJson(value)
}

private suspend fun ApplicationCall.respondPoiFeatureJson(value: PoiDetailFeatureSchema) {
    respondEncodedJson(value)
}

private suspend fun ApplicationCall.respondPoiError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondApiError(error = error, status = status, detail = detail)
}

private suspend inline fun <reified T> ApplicationCall.respondPoiJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondEncodedJson(value, status)
}
