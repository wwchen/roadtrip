package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.PoiDetailFeatureSchema
import ca.floo.roadtrip.models.api.PoiFeatureCollectionSchema
import ca.floo.roadtrip.models.api.PoiSearchHitSchema
import ca.floo.roadtrip.models.api.PoiSearchResponseSchema
import ca.floo.roadtrip.models.api.PoisRequestSchema
import ca.floo.roadtrip.models.domain.Bbox
import ca.floo.roadtrip.service.api.POI_CAMPGROUND_MIN_ZOOM
import ca.floo.roadtrip.service.api.POI_LIMIT
import ca.floo.roadtrip.service.api.PoiService
import ca.floo.roadtrip.service.api.encodePoiFeatureJson
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@OptIn(ExperimentalSerializationApi::class)
private val poiRoutesJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

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
internal fun Route.poiRoutes(poiService: PoiService) {
    post("/api/pois", {
        tags = listOf("poi")
        summary = "POIs within bbox; capped at $POI_LIMIT features (truncated:true on overflow)"
        description =
            "Body: { bbox: [w,s,e,n], zoom?, categories? }. " +
            "categories defaults to the canonical serving set. " +
            "zoom < $POI_CAMPGROUND_MIN_ZOOM suppresses campgrounds even when requested. " +
            "Corridor filtering has moved to POST /api/pois/on-route."
        request {
            body<PoisRequestSchema> {
                mediaTypes(ContentType.Application.Json)
                example("simple bbox") {
                    value =
                        PoisRequestSchema(
                            bbox = listOf(-122.6, 37.4, -121.6, 38.0),
                            zoom = 10,
                        )
                }
                example("filtered categories") {
                    value =
                        PoisRequestSchema(
                            bbox = listOf(-122.6, 37.4, -121.6, 38.0),
                            zoom = 10,
                            categories = listOf("planet_fitness_location", "tesla_supercharger"),
                        )
                }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "GeoJSON FeatureCollection. truncated:true when the bbox span exceeded the cap."
                body<PoiFeatureCollectionSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed body or missing bbox."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val bodyText = call.receiveText()
        val req =
            try {
                parseRequest(bodyText)
            } catch (e: Exception) {
                call.respondPoiError("bad_request", HttpStatusCode.BadRequest, e.message ?: "parse failed")
                return@post
            }

        call.respondPoiFeatureJson(
            poiService.pois(
                bbox = req.bbox,
                zoom = req.zoom,
                categories = req.categories,
            ),
        )
    }

    // GET /api/pois/{id}
    //
    // Per-row detail. The bbox endpoint ships only id + lat/lng + category +
    // subcategory + agency; this endpoint backs the popup/drawer "I clicked a
    // pin" flow with the full feature shape.
    get("/api/pois/{id}", {
        tags = listOf("poi")
        summary = "Full per-row POI detail (the slim bbox endpoint omits these fields)"
        description =
            "Returns one GeoJSON Feature with the wide property set. " +
            "Cacheable: max-age=300, stale-while-revalidate=3600."
        request {
            pathParameter<Long>("id") { description = "pois.id primary key" }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "GeoJSON Feature with full properties."
                body<PoiDetailFeatureSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No row with that id (or it was soft-deleted)."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondPoiError("bad_id", HttpStatusCode.BadRequest)
        val feature =
            poiService.poiDetail(id)
                ?: return@get call.respondPoiError("not_found", HttpStatusCode.NotFound)
        call.response.headers.append(
            "Cache-Control",
            "public, max-age=300, stale-while-revalidate=3600",
        )
        call.respondPoiFeatureJson(feature)
    }

    // GET /api/pois/search?q=...&limit=10
    //
    // Text-search across the full POI index by name. Used by the topbar
    // dropdown so a user can find a POI without panning to it first.
    get("/api/pois/search", {
        tags = listOf("poi")
        summary = "Text search POIs by name (cross-viewport)"
        description =
            "Returns up to `limit` matches ranked by prefix-match -> name length -> alphabetical. " +
            "Empty `q` (or shorter than 2 chars) returns an empty list. " +
            "`categories` optionally filters to one or more comma-separated POI categories. " +
            "Used by the topbar dropdown so a user can find a POI nationwide without panning to it first."
        request {
            queryParameter<String>("q") { description = "Query string, >= 2 chars" }
            queryParameter<Int>("limit") { description = "Max results, 1..25 (default 10)" }
            queryParameter<String>("categories") { description = "Optional comma-separated category filter, e.g. campground" }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Ranked match list."
                body<PoiSearchResponseSchema> {
                    mediaTypes(ContentType.Application.Json)
                    example("upper pines") {
                        value =
                            PoiSearchResponseSchema(
                                results =
                                    listOf(
                                        PoiSearchHitSchema(
                                            id = 12345,
                                            name = "Upper Pines Campground",
                                            category = "campground",
                                            region = "CA",
                                            lng = -119.5648,
                                            lat = 37.7406,
                                        ),
                                    ),
                            )
                    }
                }
            }
        }
    }) {
        val q =
            call.request.queryParameters["q"]
                ?.trim()
                .orEmpty()
        val limit =
            call.request.queryParameters["limit"]
                ?.toIntOrNull()
                ?.coerceIn(1, 25)
                ?: 10
        val categories =
            parseSearchCategories(
                call.request.queryParameters
                    .getAll("categories")
                    .orEmpty(),
            )
        call.respondPoiJson(
            poiService.search(
                query = q,
                categories = categories,
                limit = limit,
            ),
        )
    }
}

private fun parseSearchCategories(values: List<String>): List<String> =
    values
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private data class PoiRequest(
    val bbox: Bbox,
    val zoom: Int?,
    val categories: List<String>?,
)

private fun parseRequest(bodyText: String): PoiRequest {
    val dto = poiRoutesJson.decodeFromString<PoisRequestSchema>(bodyText)
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
    respondText(encodePoiFeatureJson(value), ContentType.Application.Json)
}

private suspend fun ApplicationCall.respondPoiFeatureJson(value: PoiDetailFeatureSchema) {
    respondText(encodePoiFeatureJson(value), ContentType.Application.Json)
}

private suspend fun ApplicationCall.respondPoiError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondPoiJson(ApiErrorSchema(error = error, detail = detail), status)
}

private suspend inline fun <reified T> ApplicationCall.respondPoiJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(poiRoutesJson.encodeToString(value), ContentType.Application.Json, status)
}
