package ca.floo.roadtrip.routes

import ca.floo.roadtrip.client.RoutingException
import ca.floo.roadtrip.models.api.PoiSearchHitSchema
import ca.floo.roadtrip.models.api.PoiSearchResponseSchema
import ca.floo.roadtrip.models.api.PoisRequestSchema
import ca.floo.roadtrip.models.api.RouteSchema
import ca.floo.roadtrip.models.api.WaypointSchema
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.RouteCache
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("PoiRoutes")

// Hard cap. The Mapbox/MapLibre frontend chokes on >5k features per source,
// and our POIs cover all of US/CA — the user is expected to zoom in. Returning
// truncated=true tells the client to ask the user to zoom further.
const val POI_LIMIT: Int = 2000

// Below this zoom, the campground category is suppressed regardless of what's
// in the categories list. ~12k rows nationwide — not useful at continental
// zoom and they crowd out the per-category limit budget.
private const val CG_MIN_ZOOM: Int = 6

// Mapbox cap. We mirror it client-side so a request that would 400 at
// /api/route 400s here too without hitting Mapbox.
private const val MAX_WAYPOINTS = 25

// Clamp on radius. Below ~5mi the corridor is too narrow to be useful;
// above ~100mi it's basically a bbox already and PostGIS spends its time
// in the spatial predicate instead of the index.
private const val MIN_RADIUS_MILES = 1.0
private const val MAX_RADIUS_MILES = 100.0
private const val MILES_TO_METERS = 1609.34

// POST /api/pois
//
// Returns a GeoJSON FeatureCollection of POIs in the requested bbox (and,
// when a route is supplied, inside the corridor around it). One round-trip
// per pan — the FE debounces moveend by 250ms.
//
// Categories are picked by the FE; default (when omitted) is the full set
// {campground, state-park, national-park, planet-fitness, supercharger}.
// Each requested category gets its own slot budget out of POI_LIMIT so a
// dense layer (Planet Fitness ~1.5k rows) can't starve sparser ones;
// truncated:true tells the client to ask the user to zoom in further.
//
// Corridor filtering: the FE passes `route: { waypoints, radius_miles }`,
// not a pre-buffered polygon. The polyline already lives in RouteCache
// (seeded by /api/route a moment earlier), so the BE buffers it on the
// fly via PostGIS ST_Buffer((line)::geography, meters)::geometry. Saves
// the FE from shipping several KB of buffered polygon over the wire on
// every pan.
fun Route.poiRoutes(
    ctx: DSLContext,
    routeCache: RouteCache,
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
    post("/api/pois", {
        tags = listOf("poi")
        summary = "POIs within bbox; capped at $POI_LIMIT features (truncated:true on overflow)"
        description =
            "Body: { bbox: [w,s,e,n], zoom?, categories?, route? }. " +
            "categories defaults to the union of `category` values from every enabled data_source " +
            "in config/poi-registry.yaml. " +
            "When route is present (waypoints + radius_miles), the BE looks up the cached polyline " +
            "from /api/route and buffers server-side, narrowing results to the corridor. " +
            "zoom < $CG_MIN_ZOOM suppresses campgrounds even when requested."
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
                example("with corridor (3-stop route, 30mi radius)") {
                    value =
                        PoisRequestSchema(
                            bbox = listOf(-117.0, 32.5, -111.0, 35.0),
                            zoom = 7,
                            route =
                                RouteSchema(
                                    waypoints =
                                        listOf(
                                            WaypointSchema(lat = 33.96, lng = -117.39),
                                            WaypointSchema(lat = 33.45, lng = -112.07),
                                            WaypointSchema(lat = 32.22, lng = -110.92),
                                        ),
                                    radius_miles = 30.0,
                                ),
                        )
                }
            }
        }
        response {
            code(io.ktor.http.HttpStatusCode.OK) {
                description =
                    "GeoJSON FeatureCollection. truncated:true when the bbox span exceeded the cap. " +
                    "corridor:true when a route was supplied."
                body<String> { mediaTypes(io.ktor.http.ContentType.Application.Json) }
            }
            code(io.ktor.http.HttpStatusCode.BadRequest) {
                description = "Malformed body, missing bbox, or invalid waypoints/radius."
                body<String> { mediaTypes(io.ktor.http.ContentType.Application.Json) }
            }
        }
    }) {
        val bodyText = call.receiveText()
        val req =
            try {
                parseRequest(bodyText)
            } catch (e: Exception) {
                call.respondText(
                    """{"error":"bad_request","detail":"${escapeJsonInline(e.message ?: "parse failed")}"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

        val rawCategories = req.categories
        // The CG zoom gate exists because at continental zoom (z<6) the
        // ~12k campground rows nationwide would dominate the per-category
        // budget. With a corridor active the spatial predicate already
        // narrows results to the route, so the gate isn't needed —
        // bypass it so campgrounds along a long road trip render at z=4.
        val categories =
            if (req.route == null && rawCategories != null && req.zoom != null && req.zoom < CG_MIN_ZOOM) {
                rawCategories.filter { it != "campground" }.takeIf { it.isNotEmpty() }
            } else {
                rawCategories
            }

        // Resolve the route to a LineString GeoJSON we can hand to
        // ST_GeomFromGeoJSON. RouteCache hits Mapbox only on miss; the
        // FE almost always primes the cache with a /api/route call right
        // before this. If the cache lookup fails (Mapbox unreachable,
        // bad waypoints), drop the corridor and serve bbox-only — better
        // to show too many POIs than to 5xx the whole pan.
        val (corridorLineGeoJson, corridorRadiusMeters) =
            if (req.route != null) {
                try {
                    val pairs = req.route.waypoints.map { it.lng to it.lat }
                    val polyline = routeCache.directions(pairs).coordinates
                    lineStringGeoJson(polyline) to (req.route.radiusMiles * MILES_TO_METERS)
                } catch (e: RoutingException) {
                    log.warn("route lookup failed, falling back to bbox-only: {}", e.message)
                    null to 0.0
                }
            } else {
                null to 0.0
            }
        val corridorActive = corridorLineGeoJson != null

        // Polygon corridors via ST_Buffer can rarely produce a self-
        // intersection pathology that PostGIS GEOS rejects with
        // TopologyException. Catch that, log it, and degrade to bbox-only —
        // the user sees more POIs than ideal, but never a 500.
        val result =
            if (categories?.isEmpty() == true) {
                PoiResult(emptyList(), truncated = false)
            } else {
                try {
                    fetchPois(
                        ctx = ctx,
                        bbox = req.bbox,
                        categories = categories,
                        defaultCategories = defaultCategories,
                        corridorLineGeoJson = corridorLineGeoJson,
                        corridorRadiusMeters = corridorRadiusMeters,
                        limit = POI_LIMIT,
                    )
                } catch (e: org.jooq.exception.DataAccessException) {
                    val cause = e.cause?.message.orEmpty()
                    if (corridorLineGeoJson != null && cause.contains("TopologyException")) {
                        log.warn("corridor GEOS topology fault, falling back to bbox-only: {}", cause)
                        fetchPois(
                            ctx = ctx,
                            bbox = req.bbox,
                            categories = categories,
                            defaultCategories = defaultCategories,
                            corridorLineGeoJson = null,
                            corridorRadiusMeters = 0.0,
                            limit = POI_LIMIT,
                        )
                    } else {
                        throw e
                    }
                }
            }

        call.respondText(
            buildFeatureCollection(result.rows, result.truncated, corridorActive),
            io.ktor.http.ContentType.Application.Json,
        )
    }

    get("/api/pois/health") {
        call.respondText("""{"status":"ok"}""", io.ktor.http.ContentType.Application.Json)
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
                body<String> { mediaTypes(io.ktor.http.ContentType.Application.Json) }
            }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondText(
                    """{"error":"bad_id"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
        val row =
            fetchPoiById(ctx, id)
                ?: return@get call.respondText(
                    """{"error":"not_found"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
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
            "Used by the topbar dropdown so a user can find a POI nationwide without panning to it first."
        request {
            queryParameter<String>("q") { description = "Query string, ≥ 2 chars" }
            queryParameter<Int>("limit") { description = "Max results, 1..25 (default 10)" }
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
        val terms = splitPoiSearchTerms(q)
        if (terms.isEmpty()) {
            call.respondText(
                Json.encodeToString(PoiSearchResponseSchema(results = emptyList())),
                io.ktor.http.ContentType.Application.Json,
            )
            return@get
        }
        val termPredicate = terms.joinToString("\n                      AND ") { "name ILIKE ? ESCAPE '\\'" }
        val patterns = terms.map { "%${escapeLikePattern(it)}%" }
        val prefix = "${escapeLikePattern(terms.first())}%"
        val hits =
            ctx
                .fetch(
                    """
                    SELECT id, name, category, region,
                           ST_X(geom) AS lng, ST_Y(geom) AS lat
                    FROM pois
                    WHERE deleted_at IS NULL
                      AND $termPredicate
                    ORDER BY (name ILIKE ? ESCAPE '\') DESC, length(name) ASC, name ASC
                    LIMIT ?
                    """.trimIndent(),
                    *patterns.toTypedArray(),
                    prefix,
                    limit,
                ).map { r ->
                    PoiSearchHitSchema(
                        id = (r.get("id") as Number).toLong(),
                        name = r.get("name") as String,
                        category = r.get("category") as String,
                        region = r.get("region") as String?,
                        lng = (r.get("lng") as Number).toDouble(),
                        lat = (r.get("lat") as Number).toDouble(),
                    )
                }
        call.respondText(
            Json.encodeToString(PoiSearchResponseSchema(results = hits)),
            io.ktor.http.ContentType.Application.Json,
        )
    }
}

private fun splitPoiSearchTerms(q: String): List<String> =
    q
        .split(Regex("\\s+"))
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private fun escapeLikePattern(s: String): String = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private data class PoiRequest(
    val bbox: Bbox,
    val zoom: Int?,
    val categories: List<String>?,
    val route: RouteRequest?,
)

private data class RouteRequest(
    val waypoints: List<Waypoint>,
    val radiusMiles: Double,
)

private data class Waypoint(
    val lat: Double,
    val lng: Double,
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

    val route =
        root["route"]?.jsonObject?.let { obj ->
            val wpsArr = obj["waypoints"]?.jsonArray ?: error("route.waypoints required")
            require(wpsArr.size in 2..MAX_WAYPOINTS) {
                "route.waypoints must have 2..$MAX_WAYPOINTS entries (got ${wpsArr.size})"
            }
            val waypoints =
                wpsArr.mapIndexed { i, el ->
                    val o = el.jsonObject
                    val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: error("waypoint[$i].lat is missing or not a number")
                    val lng = o["lng"]?.jsonPrimitive?.doubleOrNull ?: error("waypoint[$i].lng is missing or not a number")
                    require(lat in -90.0..90.0) { "waypoint[$i].lat out of range" }
                    require(lng in -180.0..180.0) { "waypoint[$i].lng out of range" }
                    Waypoint(lat = lat, lng = lng)
                }
            val radius =
                obj["radius_miles"]?.jsonPrimitive?.doubleOrNull
                    ?: error("route.radius_miles is missing or not a number")
            require(radius in MIN_RADIUS_MILES..MAX_RADIUS_MILES) {
                "route.radius_miles must be in [$MIN_RADIUS_MILES, $MAX_RADIUS_MILES] (got $radius)"
            }
            RouteRequest(waypoints = waypoints, radiusMiles = radius)
        }

    return PoiRequest(bbox, zoom, categories, route)
}

data class Bbox(
    val west: Double,
    val south: Double,
    val east: Double,
    val north: Double,
)

internal fun parseBbox(raw: String): Bbox? {
    val parts = raw.split(',')
    if (parts.size != 4) return null
    val nums = parts.map { it.trim().toDoubleOrNull() ?: return null }
    val (w, s, e, n) = nums
    if (w !in -180.0..180.0 || e !in -180.0..180.0) return null
    if (s !in -90.0..90.0 || n !in -90.0..90.0) return null
    if (w >= e || s >= n) return null
    return Bbox(w, s, e, n)
}

// Slim row shape for the bbox endpoint. Just enough for MapLibre to place
// + color a pin: id, lat/lng, category for color band, subcategory for the
// campground sub-bucket. Everything richer (name, address, provider_ref,
// raw properties) lives behind GET /api/pois/{id} and is fetched lazily on
// pin click.
internal data class PoiRow(
    val id: Long,
    val category: String,
    val subcategory: String?,
    val lng: Double,
    val lat: Double,
)

// Wide row shape returned by GET /api/pois/{id}. Same projection the bbox
// endpoint used to ship for every row; now paid for only when the user
// actually opens a pin.
internal data class PoiDetailRow(
    val id: Long,
    val source: String,
    val sourceId: String,
    val category: String,
    val subcategory: String?,
    val name: String,
    val region: String?,
    val unitName: String?,
    val reserveUrl: String?,
    val phone: String?,
    val infoUrl: String?,
    // JSONB ::text — null when the row has no address bag (most parks).
    val addressJson: String?,
    // JSONB ::text — null for non-bookable POIs (PF, parks). Carries the
    // sealed ProviderRef variant so the drawer can render booking deep
    // links + availability heat strips for the matching adapter.
    val providerRefJson: String? = null,
    val geomJson: String,
    val propertiesJson: String,
)

// Outcome of a sampled bbox fetch. `truncated` is true whenever the raw
// count exceeded the global cap — even if the sample fits under it — so
// the FE can show "zoom in for more" honestly.
internal data class PoiResult(
    val rows: List<PoiRow>,
    val truncated: Boolean,
)

// Spatial sampling grid. 10x10 = 100 cells. Each (cell, category) gets up
// to ceil(category_allocation / GRID_CELLS) rows, so the slim payload comes
// back spread across the viewport instead of clumped wherever Postgres's
// row order happened to put the first matches.
private const val SAMPLE_GRID_DIM: Int = 10
private const val SAMPLE_GRID_CELLS: Int = SAMPLE_GRID_DIM * SAMPLE_GRID_DIM

// Even when one category dominates the viewport (e.g. campgrounds at
// continental zoom), every present category gets at least this many slots
// so the legend's sparse layers don't disappear from the response.
private const val MIN_PER_CATEGORY_ALLOCATION: Int = 50

internal fun fetchPois(
    ctx: DSLContext,
    bbox: Bbox,
    categories: List<String>?,
    defaultCategories: List<String>,
    corridorLineGeoJson: String? = null,
    corridorRadiusMeters: Double = 0.0,
    limit: Int,
): PoiResult {
    val cats = categories ?: defaultCategories
    if (cats.isEmpty()) return PoiResult(emptyList(), truncated = false)

    // Step 1: cheap per-category COUNT in the bbox/corridor. GIST-only on
    // the geom predicate, no row reads beyond the index. Drives both the
    // truncation flag and the proportional allocation in step 2.
    val countByCat = countByCategory(ctx, cats, bbox, corridorLineGeoJson, corridorRadiusMeters)
    val rawTotal = countByCat.values.sum()
    val present = cats.filter { (countByCat[it] ?: 0) > 0 }
    if (present.isEmpty()) return PoiResult(emptyList(), truncated = false)

    // Step 2: distribute the cap across present categories proportional to
    // viewport presence. Each present category gets a minimum guarantee so
    // sparse layers (e.g. 5 superchargers in a campground-heavy region)
    // don't get rounded to zero. Remainder split by share of the rest.
    val allocation = allocateBudget(present.associateWith { countByCat[it] ?: 0 }, limit)

    // Step 3: pull the actual rows. Each per-category subquery uses a
    // row_number() window partitioned by a SAMPLE_GRID_DIM x SAMPLE_GRID_DIM
    // spatial bucket to spread the sample across the viewport. UNION ALL
    // collapses the per-cat results into one round-trip.
    val rows =
        fetchSampled(
            ctx = ctx,
            bbox = bbox,
            allocation = allocation,
            corridorLineGeoJson = corridorLineGeoJson,
            corridorRadiusMeters = corridorRadiusMeters,
        )
    // truncated = "we returned fewer rows than the viewport contains" —
    // covers both the cap-overflow case (raw > limit) and the spatial-
    // bucket case (raw fits but sampling thinned a tight cluster).
    val truncated = rows.size < rawTotal
    return PoiResult(rows, truncated)
}

private fun countByCategory(
    ctx: DSLContext,
    cats: List<String>,
    bbox: Bbox,
    corridorLineGeoJson: String?,
    corridorRadiusMeters: Double,
): Map<String, Int> {
    if (cats.isEmpty()) return emptyMap()
    val placeholders = cats.joinToString(",") { "?" }
    val sql =
        buildString {
            append(
                """
                SELECT category, COUNT(*) AS n
                FROM pois
                WHERE deleted_at IS NULL
                  AND category IN ($placeholders)
                  AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                """.trimIndent(),
            )
            if (corridorLineGeoJson != null) {
                append(
                    "\n      AND ST_Intersects(geom, " +
                        "ST_CollectionExtract(ST_MakeValid(" +
                        "ST_Buffer(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography, ?)::geometry" +
                        "), 3))",
                )
            }
            append("\nGROUP BY category")
        }
    val args = mutableListOf<Any>()
    args.addAll(cats)
    args.add(bbox.west)
    args.add(bbox.south)
    args.add(bbox.east)
    args.add(bbox.north)
    if (corridorLineGeoJson != null) {
        args.add(corridorLineGeoJson)
        args.add(corridorRadiusMeters)
    }
    val out = mutableMapOf<String, Int>()
    for (r in ctx.fetch(sql, *args.toTypedArray())) {
        out[r.get("category") as String] = (r.get("n") as Number).toInt()
    }
    return out
}

/**
 * Distribute a global cap across categories with viewport presence:
 *
 *   - Sparse layers get `MIN_PER_CATEGORY_ALLOCATION` (or their full count if
 *     less) so they remain visible in legends even when one category swamps.
 *   - Remaining budget split proportional to (count - already-allocated)
 *     across the categories above the minimum.
 *
 * Returns a map of category → max-rows. Sum of values <= cap.
 */
private fun allocateBudget(
    presentCounts: Map<String, Int>,
    cap: Int,
): Map<String, Int> {
    val baseline = presentCounts.mapValues { (_, n) -> minOf(n, MIN_PER_CATEGORY_ALLOCATION) }
    val baselineSum = baseline.values.sum()
    if (baselineSum >= cap) {
        // The cap is so small we can't even fund every category's minimum.
        // Still cover them; a tiny over-allocation in pathological cases is
        // fine — the per-cat LIMIT still bounds each subquery.
        return baseline
    }
    val remaining = cap - baselineSum
    val excess = presentCounts.mapValues { (k, n) -> (n - baseline.getValue(k)).coerceAtLeast(0) }
    val excessTotal = excess.values.sum()
    if (excessTotal == 0) return baseline
    val extra =
        excess.mapValues { (_, e) ->
            ((e.toLong() * remaining) / excessTotal).toInt()
        }
    return presentCounts.mapValues { (k, _) -> baseline.getValue(k) + (extra[k] ?: 0) }
}

private fun fetchSampled(
    ctx: DSLContext,
    bbox: Bbox,
    allocation: Map<String, Int>,
    corridorLineGeoJson: String?,
    corridorRadiusMeters: Double,
): List<PoiRow> {
    val cats = allocation.keys.toList()
    if (cats.isEmpty()) return emptyList()

    // Cell width/height in degrees for the spatial bucket window. Matches
    // SAMPLE_GRID_DIM cells across the bbox in each axis.
    val dx = (bbox.east - bbox.west) / SAMPLE_GRID_DIM
    val dy = (bbox.north - bbox.south) / SAMPLE_GRID_DIM
    val sql =
        buildString {
            cats.forEachIndexed { idx, _ ->
                if (idx > 0) append("\nUNION ALL\n")
                append("(SELECT id, category, subcategory, lng, lat FROM (")
                append(
                    """
                    SELECT id, category, subcategory,
                           ST_X(ST_Centroid(geom)) AS lng,
                           ST_Y(ST_Centroid(geom)) AS lat,
                           row_number() OVER (
                             PARTITION BY
                               floor((ST_X(ST_Centroid(geom)) - ?) / ?)::int,
                               floor((ST_Y(ST_Centroid(geom)) - ?) / ?)::int
                             ORDER BY id
                           ) AS rn
                    FROM pois
                    WHERE deleted_at IS NULL
                      AND category = ?
                      AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                    """.trimIndent(),
                )
                if (corridorLineGeoJson != null) {
                    append(
                        "\n      AND ST_Intersects(geom, " +
                            "ST_CollectionExtract(ST_MakeValid(" +
                            "ST_Buffer(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography, ?)::geometry" +
                            "), 3))",
                    )
                }
                append("\n) sub WHERE rn <= ? LIMIT ?)")
            }
        }

    val args = mutableListOf<Any>()
    for (cat in cats) {
        // 4 args for the spatial-bucket math: (west, dx, south, dy). dy=0
        // would only happen on a zero-height bbox, which the validator
        // already rejects.
        args.add(bbox.west)
        args.add(dx)
        args.add(bbox.south)
        args.add(dy)
        args.add(cat)
        args.add(bbox.west)
        args.add(bbox.south)
        args.add(bbox.east)
        args.add(bbox.north)
        if (corridorLineGeoJson != null) {
            args.add(corridorLineGeoJson)
            args.add(corridorRadiusMeters)
        }
        val cap = allocation.getValue(cat).coerceAtLeast(1)
        // Per-cell cap. ceil(cap / cells) so we don't undershoot when cap
        // doesn't divide evenly. When the cap comfortably covers everything
        // (cap >= total cells * something reasonable), the spatial sampling
        // becomes a soft cap; the LIMIT clause is the actual ceiling.
        // Use cap itself for perCell when cap is small relative to cells —
        // a tight cluster of e.g. 50 points shouldn't get rn-filtered down
        // to 1 per cell.
        val perCell =
            if (cap <= SAMPLE_GRID_CELLS) {
                // Tight cluster / small allocation: don't sample; let LIMIT win.
                cap
            } else {
                (cap + SAMPLE_GRID_CELLS - 1) / SAMPLE_GRID_CELLS
            }
        args.add(perCell.coerceAtLeast(1))
        args.add(cap)
    }

    return ctx.fetch(sql, *args.toTypedArray()).map { r ->
        PoiRow(
            id = (r.get("id") as Number).toLong(),
            category = r.get("category") as String,
            subcategory = r.get("subcategory") as String?,
            lng = (r.get("lng") as Number).toDouble(),
            lat = (r.get("lat") as Number).toDouble(),
        )
    }
}

/**
 * Fetch the full, drawer-ready row for one POI. Returns null when the id
 * is unknown or the row is soft-deleted. Mirrors the wide projection the
 * bbox endpoint used to ship for every row.
 */
internal fun fetchPoiById(
    ctx: DSLContext,
    poiId: Long,
): PoiDetailRow? {
    val r =
        ctx.fetchOne(
            """
            SELECT id, source, source_id, category, subcategory, name,
                   region, unit_name, reserve_url, phone, info_url,
                   address::text AS address_text,
                   provider_ref::text AS provider_ref_text,
                   ST_AsGeoJSON(geom) AS geom_json,
                   properties::text AS properties_text
            FROM pois
            WHERE id = ?
              AND deleted_at IS NULL
            """.trimIndent(),
            poiId,
        ) ?: return null
    return PoiDetailRow(
        id = (r.get("id") as Number).toLong(),
        source = r.get("source") as String,
        sourceId = r.get("source_id") as String,
        category = r.get("category") as String,
        subcategory = r.get("subcategory") as String?,
        name = r.get("name") as String,
        region = r.get("region") as String?,
        unitName = r.get("unit_name") as String?,
        reserveUrl = r.get("reserve_url") as String?,
        phone = r.get("phone") as String?,
        infoUrl = r.get("info_url") as String?,
        addressJson = r.get("address_text") as String?,
        providerRefJson = r.get("provider_ref_text") as String?,
        geomJson = r.get("geom_json") as String,
        propertiesJson = r.get("properties_text") as String,
    )
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
    corridorActive: Boolean = false,
): String {
    val sb = StringBuilder()
    sb
        .append("""{"type":"FeatureCollection","truncated":""")
        .append(truncated)
    if (corridorActive) sb.append(""","corridor":true""")
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

private fun lineStringGeoJson(coords: List<List<Double>>): String =
    buildString {
        append("""{"type":"LineString","coordinates":[""")
        for ((i, c) in coords.withIndex()) {
            if (i > 0) append(',')
            append('[')
                .append(c[0])
                .append(',')
                .append(c[1])
                .append(']')
        }
        append("]}")
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

private fun escapeJsonInline(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
