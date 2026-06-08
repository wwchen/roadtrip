package ca.floo.roadtrip.api

import ca.floo.roadtrip.route.RouteCache
import ca.floo.roadtrip.route.RoutingException
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
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
) {
    post("/api/pois", {
        tags = listOf("poi")
        summary = "POIs within bbox; capped at $POI_LIMIT features (truncated:true on overflow)"
        description =
            "Body: { bbox: [w,s,e,n], zoom?, categories?, route? }. " +
            "categories defaults to {campground, state-park, national-park, planet-fitness, supercharger}. " +
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
        val categories =
            if (rawCategories != null && req.zoom != null && req.zoom < CG_MIN_ZOOM) {
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
        val rows =
            if (categories?.isEmpty() == true) {
                emptyList()
            } else {
                try {
                    fetchPois(
                        ctx = ctx,
                        bbox = req.bbox,
                        categories = categories,
                        corridorLineGeoJson = corridorLineGeoJson,
                        corridorRadiusMeters = corridorRadiusMeters,
                        limit = POI_LIMIT + 1,
                    )
                } catch (e: org.jooq.exception.DataAccessException) {
                    val cause = e.cause?.message.orEmpty()
                    if (corridorLineGeoJson != null && cause.contains("TopologyException")) {
                        log.warn("corridor GEOS topology fault, falling back to bbox-only: {}", cause)
                        fetchPois(
                            ctx = ctx,
                            bbox = req.bbox,
                            categories = categories,
                            corridorLineGeoJson = null,
                            corridorRadiusMeters = 0.0,
                            limit = POI_LIMIT + 1,
                        )
                    } else {
                        throw e
                    }
                }
            }
        val truncated = rows.size > POI_LIMIT
        val effective = if (truncated) rows.take(POI_LIMIT) else rows

        call.respondText(
            buildFeatureCollection(effective, truncated, corridorActive),
            io.ktor.http.ContentType.Application.Json,
        )
    }

    get("/api/pois/health") {
        call.respondText("""{"status":"ok"}""", io.ktor.http.ContentType.Application.Json)
    }
}

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

internal data class PoiRow(
    val id: Long,
    val source: String,
    val sourceId: String,
    val category: String,
    val name: String,
    val region: String?,
    val unitName: String?,
    val reserveUrl: String?,
    val geomJson: String,
    val propertiesJson: String,
)

internal fun fetchPois(
    ctx: DSLContext,
    bbox: Bbox,
    categories: List<String>?,
    corridorLineGeoJson: String? = null,
    corridorRadiusMeters: Double = 0.0,
    limit: Int,
): List<PoiRow> {
    // Per-category limit: each requested category gets its own slot budget,
    // so a dataset like Planet Fitness (1.5k rows) can't starve Superchargers
    // or Campgrounds when bbox is continental.
    val cats =
        categories ?: listOf(
            "campground",
            "state-park",
            "national-park",
            "planet-fitness",
            "supercharger",
        )
    if (cats.isEmpty()) return emptyList()

    val perCategoryLimit = (limit / cats.size).coerceAtLeast(1)

    // UNION ALL across N category-scoped subqueries, each capped. Same
    // total round-trip cost as a single query, but truncation is per-cat
    // instead of "first to the row planner."
    val sql =
        buildString {
            cats.forEachIndexed { idx, _ ->
                if (idx > 0) append("\nUNION ALL\n")
                append("(")
                append(
                    """
                    SELECT id, source, source_id, category, name, region, unit_name,
                           reserve_url, ST_AsGeoJSON(geom) AS geom_json,
                           properties::text AS properties_text
                    FROM pois
                    WHERE deleted_at IS NULL
                      AND category = ?
                      AND geom && ST_MakeEnvelope(?, ?, ?, ?, 4326)
                    """.trimIndent(),
                )
                if (corridorLineGeoJson != null) {
                    // Cast to geography so ST_Buffer takes meters (Web
                    // Mercator buffering distorts hugely at high latitudes;
                    // geography path is correct everywhere). Cast back to
                    // geometry for the index-friendly ST_Intersects.
                    //
                    // ST_MakeValid + ST_CollectionExtract guard against the
                    // self-intersection pathology that buffering a route
                    // doubling back can produce. CollectionExtract(_, 3)
                    // keeps only polygonal parts so the predicate evaluates
                    // cleanly even when ST_MakeValid emits a GeometryCollection.
                    append(
                        "\n      AND ST_Intersects(geom, " +
                            "ST_CollectionExtract(ST_MakeValid(" +
                            "ST_Buffer(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography, ?)::geometry" +
                            "), 3))",
                    )
                }
                append("\n    LIMIT ?")
                append(")")
            }
        }

    val args = mutableListOf<Any>()
    for (cat in cats) {
        args.add(cat)
        args.add(bbox.west)
        args.add(bbox.south)
        args.add(bbox.east)
        args.add(bbox.north)
        if (corridorLineGeoJson != null) {
            args.add(corridorLineGeoJson)
            args.add(corridorRadiusMeters)
        }
        args.add(perCategoryLimit)
    }

    return ctx.fetch(sql, *args.toTypedArray()).map { r ->
        PoiRow(
            id = (r.get("id") as Number).toLong(),
            source = r.get("source") as String,
            sourceId = r.get("source_id") as String,
            category = r.get("category") as String,
            name = r.get("name") as String,
            region = r.get("region") as String?,
            unitName = r.get("unit_name") as String?,
            reserveUrl = r.get("reserve_url") as String?,
            geomJson = r.get("geom_json") as String,
            propertiesJson = r.get("properties_text") as String,
        )
    }
}

internal fun buildFeatureCollection(
    rows: List<PoiRow>,
    truncated: Boolean,
    corridorActive: Boolean = false,
): String {
    // Hand-built JSON. Properties come in as a JSONB ::text and are merged
    // into the feature's properties object — building this with kotlinx
    // would require parsing+re-serializing the JSONB on every row, and the
    // bbox endpoint is hot.
    val sb = StringBuilder()
    sb
        .append("""{"type":"FeatureCollection","truncated":""")
        .append(truncated)
    if (corridorActive) sb.append(""","corridor":true""")
    sb.append(""","features":[""")
    for ((i, r) in rows.withIndex()) {
        if (i > 0) sb.append(',')
        sb.append("""{"type":"Feature","id":""").append(r.id)
        sb.append(""","geometry":""").append(r.geomJson)
        sb.append(""","properties":{"source":""").append(jsonString(r.source))
        sb.append(""","source_id":""").append(jsonString(r.sourceId))
        sb.append(""","category":""").append(jsonString(r.category))
        sb.append(""","name":""").append(jsonString(r.name))
        if (r.region != null) sb.append(""","region":""").append(jsonString(r.region))
        if (r.unitName != null) sb.append(""","unit_name":""").append(jsonString(r.unitName))
        if (r.reserveUrl != null) sb.append(""","reserve_url":""").append(jsonString(r.reserveUrl))
        sb.append(""","raw":""").append(r.propertiesJson)
        sb.append("}}")
    }
    sb.append("]}")
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
