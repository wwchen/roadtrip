package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.PoiDetailFeatureSchema
import ca.floo.roadtrip.models.api.PoiDetailPropertiesSchema
import ca.floo.roadtrip.models.api.PoiFeatureCollectionSchema
import ca.floo.roadtrip.models.api.PoiSearchHitSchema
import ca.floo.roadtrip.models.api.PoiSearchResponseSchema
import ca.floo.roadtrip.models.api.PointGeometrySchema
import ca.floo.roadtrip.models.api.PoisRequestSchema
import ca.floo.roadtrip.models.api.SlimPoiFeatureSchema
import ca.floo.roadtrip.models.api.SlimPoiPropertiesSchema
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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        ignoreUnknownKeys = true
        explicitNulls = false
    }

@OptIn(ExperimentalSerializationApi::class)
private val poiFeatureJson =
    Json {
        encodeDefaults = true
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
                body<PoiFeatureCollectionSchema> { mediaTypes(io.ktor.http.ContentType.Application.Json) }
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

        call.respondPoiFeatureJson(poiFeatureCollection(result.rows, result.truncated))
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
                body<PoiDetailFeatureSchema> { mediaTypes(io.ktor.http.ContentType.Application.Json) }
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
        call.respondPoiFeatureJson(poiDetailFeature(row))
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
            call.respondPoiJson(PoiSearchResponseSchema(results = emptyList()))
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
        call.respondPoiJson(PoiSearchResponseSchema(results = hits))
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

internal fun poiFeatureCollection(
    rows: List<PoiRow>,
    truncated: Boolean,
): PoiFeatureCollectionSchema =
    PoiFeatureCollectionSchema(
        truncated = truncated,
        features =
            rows.map { row ->
                SlimPoiFeatureSchema(
                    id = row.id,
                    geometry = PointGeometrySchema(coordinates = listOf(row.lng, row.lat)),
                    properties =
                        SlimPoiPropertiesSchema(
                            category = row.category,
                            subcategory = row.subcategory,
                        ),
                )
            },
    )

internal fun poiDetailFeature(r: PoiDetailRow): PoiDetailFeatureSchema =
    PoiDetailFeatureSchema(
        id = r.id,
        geometry = Json.parseToJsonElement(r.geomJson),
        properties =
            PoiDetailPropertiesSchema(
                source = r.source,
                sourceId = r.sourceId,
                category = r.category,
                subcategory = r.subcategory,
                name = r.name,
                region = r.region,
                unitName = r.unitName,
                reserveUrl = r.reserveUrl,
                phone = r.phone,
                infoUrl = r.infoUrl,
                address = r.addressJson?.let { Json.parseToJsonElement(it) },
                providerRef = r.providerRefJson?.let { Json.parseToJsonElement(it) },
                raw = Json.parseToJsonElement(r.propertiesJson),
            ),
    )

internal fun encodePoiFeatureJson(value: PoiFeatureCollectionSchema): String = poiFeatureJson.encodeToString(value)

internal fun encodePoiFeatureJson(value: PoiDetailFeatureSchema): String = poiFeatureJson.encodeToString(value)

private suspend inline fun <reified T> ApplicationCall.respondPoiFeatureJson(value: T) {
    respondText(poiFeatureJson.encodeToString(value), ContentType.Application.Json)
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
