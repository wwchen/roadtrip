package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.PoiSearchHitSchema
import ca.floo.roadtrip.models.api.PoiSearchResponseSchema
import ca.floo.roadtrip.models.api.PoisRequestSchema
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.Bbox
import ca.floo.roadtrip.repo.PoiDetailRow
import ca.floo.roadtrip.repo.PoiRow
import ca.floo.roadtrip.repo.PoiServingRepo
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext

// Hard cap. The Mapbox/MapLibre frontend chokes on >5k features per source,
// and our POIs cover all of US/CA — the user is expected to zoom in. Returning
// truncated=true tells the client to ask the user to zoom further.
const val POI_LIMIT: Int = 2000

// Below this zoom, the campground category is suppressed regardless of what's
// in the categories list. ~12k rows nationwide — not useful at continental
// zoom and they crowd out the per-category limit budget.
private const val CG_MIN_ZOOM: Int = 6

@OptIn(ExperimentalSerializationApi::class)
private val poiRoutesJson =
    Json {
        explicitNulls = false
    }

// POST /api/pois
//
// Returns a GeoJSON FeatureCollection of POIs in the requested bbox.
// One round-trip per pan — the FE debounces moveend by 250ms.
//
// Categories are picked by the FE; default (when omitted) is the full set
// {campground, state-park, national-park, planet-fitness, supercharger}.
// Each requested category gets its own slot budget out of POI_LIMIT so a
// dense layer (Planet Fitness ~1.5k rows) can't starve sparser ones;
// truncated:true tells the client to ask the user to zoom in further.
//
// Corridor filtering moved to POST /api/pois/on-route — the trip
// planner's "campgrounds along route" list needs the full set, not a
// viewport slice + per-cat sample, so the two paths have different
// truncation rules and live in different endpoints.
fun Route.poiRoutes(
    ctx: DSLContext,
    registry: PoiRegistry,
) {
    // Default category list derives from the YAML registry so a new
    // poi_data category surfaces without code changes.
    val defaultCategories: List<String> =
        registry
            .enabledPoiData()
            .map { it.category }
            .toSet()
            .toList()
    val poiRepo = PoiServingRepo(ctx)
    post("/api/pois", {
        tags = listOf("poi")
        summary = "POIs within bbox; capped at $POI_LIMIT features (truncated:true on overflow)"
        description =
            "Body: { bbox: [w,s,e,n], zoom?, categories? }. " +
            "categories defaults to the union of `category` values from every enabled data_source " +
            "in config/poi-registry.yaml. " +
            "zoom < $CG_MIN_ZOOM suppresses campgrounds even when requested. " +
            "Corridor filtering has moved to POST /api/pois/on-route."
        request {
            body<PoisRequestSchema> {
                mediaTypes(io.ktor.http.ContentType.Application.Json)
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
                            categories = listOf("planet-fitness", "supercharger"),
                        )
                }
            }
        }
        response {
            code(io.ktor.http.HttpStatusCode.OK) {
                description =
                    "GeoJSON FeatureCollection. truncated:true when the bbox span exceeded the cap."
                body<String> { mediaTypes(io.ktor.http.ContentType.Application.Json) }
            }
            code(io.ktor.http.HttpStatusCode.BadRequest) {
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

        val rawCategories = req.categories
        // CG zoom gate: at continental zoom (z<6) the ~12k campground rows
        // nationwide would dominate the per-category budget. The trip
        // planner's "campgrounds along route" view bypasses this entirely
        // by going through /api/pois/on-route, so the gate here is a pure
        // viewport-rendering decision.
        val categories =
            if (rawCategories != null && req.zoom != null && req.zoom < CG_MIN_ZOOM) {
                rawCategories.filter { it != "campground" }.takeIf { it.isNotEmpty() }
            } else {
                rawCategories
            }

        val result =
            poiRepo.fetchPois(
                bbox = req.bbox,
                categories = categories,
                defaultCategories = defaultCategories,
                limit = POI_LIMIT,
            )

        call.respondText(
            buildFeatureCollection(result.rows, result.truncated),
            io.ktor.http.ContentType.Application.Json,
        )
    }

    // GET /api/pois/{id}
    //
    // Per-row detail. The bbox endpoint ships only id + lat/lng + category +
    // subcategory; this endpoint backs the popup/drawer "I clicked a pin"
    // flow with the full feature shape (name, address, provider_ref, raw
    // properties blob — everything the legacy bbox response carried).
    //
    // Cache-Control gives the browser a 5-minute fresh window plus 1h SWR.
    // Per-row content rarely changes day-to-day; this collapses repeat-
    // clicks of the same pin to a single network round-trip per session.
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
            code(io.ktor.http.HttpStatusCode.OK) {
                description = "GeoJSON Feature with full properties."
                body<String> { mediaTypes(io.ktor.http.ContentType.Application.Json) }
            }
            code(io.ktor.http.HttpStatusCode.NotFound) {
                description = "No row with that id (or it was soft-deleted)."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondPoiError("bad_id", HttpStatusCode.BadRequest)
        val row =
            poiRepo.fetchPoiById(id)
                ?: return@get call.respondPoiError("not_found", HttpStatusCode.NotFound)
        call.response.headers.append(
            "Cache-Control",
            "public, max-age=300, stale-while-revalidate=3600",
        )
        call.respondText(
            buildSingleFeatureJson(row),
            io.ktor.http.ContentType.Application.Json,
        )
    }

    // GET /api/pois/search?q=...&limit=10
    //
    // Text-search across the full pois table by name. Used by the topbar
    // dropdown so a user can find a POI like "upper pines" without having
    // panned to Yosemite first — the bbox endpoint only sees the current
    // viewport, so a cross-country query needs a separate path.
    //
    // Ranking: prefix match wins, then name length, then alphabetical.
    // Cheap on a 12k-row table; if pois grows we can swap in pg_trgm + GIN.
    get("/api/pois/search", {
        tags = listOf("poi")
        summary = "Text search POIs by name (cross-viewport)"
        description =
            "Returns up to `limit` matches ranked by prefix-match → name length → alphabetical. " +
            "Empty `q` (or shorter than 2 chars) returns an empty list. " +
            "`categories` optionally filters to one or more comma-separated POI categories. " +
            "Used by the topbar dropdown so a user can find a POI nationwide without panning to it first."
        request {
            queryParameter<String>("q") { description = "Query string, ≥ 2 chars" }
            queryParameter<Int>("limit") { description = "Max results, 1..25 (default 10)" }
            queryParameter<String>("categories") { description = "Optional comma-separated category filter, e.g. campground" }
        }
        response {
            code(io.ktor.http.HttpStatusCode.OK) {
                description = "Ranked match list."
                body<PoiSearchResponseSchema> {
                    mediaTypes(io.ktor.http.ContentType.Application.Json)
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
        if (q.length < 2) {
            call.respondText(
                Json.encodeToString(PoiSearchResponseSchema(results = emptyList())),
                io.ktor.http.ContentType.Application.Json,
            )
            return@get
        }
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
        val hits =
            poiRepo
                .search(query = q, categories = categories, limit = limit)
                .map {
                    PoiSearchHitSchema(
                        id = it.id,
                        name = it.name,
                        category = it.category,
                        region = it.region,
                        lng = it.lng,
                        lat = it.lat,
                    )
                }
        call.respondText(
            Json.encodeToString(PoiSearchResponseSchema(results = hits)),
            io.ktor.http.ContentType.Application.Json,
        )
    }
}

private fun parseSearchCategories(values: List<String>): List<String> =
    values
        .flatMap { it.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

private data class PoiRequest(
    val bbox: Bbox,
    val zoom: Int?,
    val categories: List<String>?,
)

private fun parseRequest(bodyText: String): PoiRequest {
    val root = Json.parseToJsonElement(bodyText).jsonObject

    val bboxArr = root["bbox"]?.jsonArray ?: error("missing bbox")
    require(bboxArr.size == 4) { "bbox must be [west,south,east,north]" }
    val nums = bboxArr.map { it.jsonPrimitive.doubleOrNull ?: error("bbox values must be numbers") }
    val (w, s, e, n) = nums
    require(w in -180.0..180.0 && e in -180.0..180.0) { "bbox lng out of range" }
    require(s in -90.0..90.0 && n in -90.0..90.0) { "bbox lat out of range" }
    require(w < e && s < n) { "bbox: west must be < east, south < north" }
    val bbox = Bbox(w, s, e, n)

    val zoom = root["zoom"]?.jsonPrimitive?.intOrNull

    val categories =
        root["categories"]
            ?.jsonArray
            ?.mapNotNull {
                it.jsonPrimitive.contentOrNull
                    ?.trim()
                    ?.takeIf { c -> c.isNotEmpty() }
            }?.takeIf { it.isNotEmpty() }

    return PoiRequest(bbox, zoom, categories)
}

/**
 * Slim FeatureCollection for the bbox endpoint. Hand-built JSON; the goal
 * is to ship the smallest possible payload that still drives the map's pin
 * placement + color logic.
 *
 * Per-feature shape:
 *
 *     {"type":"Feature","id":42,
 *      "geometry":{"type":"Point","coordinates":[lng,lat]},
 *      "properties":{"category":"campground","subcategory":"federal"}}
 *
 * No name, no address, no provider_ref, no raw blob — the FE fetches that
 * lazily via GET /api/pois/{id} when a pin is clicked.
 */
internal fun buildFeatureCollection(
    rows: List<PoiRow>,
    truncated: Boolean,
): String {
    val sb = StringBuilder()
    sb
        .append("""{"type":"FeatureCollection","truncated":""")
        .append(truncated)
    sb.append(""","features":[""")
    for ((i, r) in rows.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append("""{"type":"Feature","id":""").append(r.id)
        sb
            .append(""","geometry":{"type":"Point","coordinates":[""")
            .append(r.lng)
            .append(',')
            .append(r.lat)
            .append("]}")
        sb.append(""","properties":{"category":""").append(jsonString(r.category))
        if (r.subcategory != null) sb.append(""","subcategory":""").append(jsonString(r.subcategory))
        sb.append("}}")
    }
    sb.append("]}")
    return sb.toString()
}

/**
 * Per-id detail JSON: the wide shape the bbox endpoint used to ship every
 * row. Same hand-built renderer + the same property merging logic; this
 * lives behind GET /api/pois/{id} so the FE only pays the cost on click.
 */
internal fun buildSingleFeatureJson(r: PoiDetailRow): String {
    val sb = StringBuilder()
    sb.append("""{"type":"Feature","id":""").append(r.id)
    sb.append(""","geometry":""").append(r.geomJson)
    sb.append(""","properties":{"source":""").append(jsonString(r.source))
    sb.append(""","source_id":""").append(jsonString(r.sourceId))
    sb.append(""","category":""").append(jsonString(r.category))
    if (r.subcategory != null) sb.append(""","subcategory":""").append(jsonString(r.subcategory))
    sb.append(""","name":""").append(jsonString(r.name))
    if (r.region != null) sb.append(""","region":""").append(jsonString(r.region))
    if (r.unitName != null) sb.append(""","unit_name":""").append(jsonString(r.unitName))
    if (r.reserveUrl != null) sb.append(""","reserve_url":""").append(jsonString(r.reserveUrl))
    if (r.phone != null) sb.append(""","phone":""").append(jsonString(r.phone))
    if (r.infoUrl != null) sb.append(""","info_url":""").append(jsonString(r.infoUrl))
    if (r.addressJson != null) sb.append(""","address":""").append(r.addressJson)
    if (r.providerRefJson != null) sb.append(""","provider_ref":""").append(r.providerRefJson)
    sb.append(""","raw":""").append(r.propertiesJson)
    sb.append("}}")
    return sb.toString()
}

private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

private suspend fun ApplicationCall.respondPoiError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondText(
        poiRoutesJson.encodeToString(ApiErrorSchema(error = error, detail = detail)),
        ContentType.Application.Json,
        status,
    )
}
