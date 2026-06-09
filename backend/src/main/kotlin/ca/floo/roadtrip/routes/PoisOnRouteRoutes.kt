package ca.floo.roadtrip.routes

import ca.floo.roadtrip.client.RoutingException
import ca.floo.roadtrip.models.api.PoisOnRouteRequestSchema
import ca.floo.roadtrip.models.api.WaypointSchema
import ca.floo.roadtrip.models.registry.PoiRegistry
import ca.floo.roadtrip.repo.RouteCache
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException
import org.slf4j.LoggerFactory

private val onRouteLog = LoggerFactory.getLogger("PoisOnRouteRoutes")

// Mapbox cap. We mirror it client-side so a request that would 400 at
// /api/route 400s here too without hitting Mapbox.
private const val MAX_WAYPOINTS = 25

// Clamp on radius. Below ~5mi the corridor is too narrow to be useful;
// above ~100mi it's basically a bbox already and PostGIS spends its time
// in the spatial predicate instead of the index.
private const val MIN_RADIUS_MILES = 1.0
private const val MAX_RADIUS_MILES = 100.0
private const val MILES_TO_METERS = 1609.34

// POST /api/pois/on-route
//
// Returns every POI inside the buffered route corridor — no viewport bound,
// no per-category cap. Drives the trip planner's "campgrounds along route"
// card list, which the user wants to scan end-to-end instead of pan-by-pan.
fun Route.poisOnRouteRoutes(
    ctx: DSLContext,
    routeCache: RouteCache,
    registry: PoiRegistry,
) {
    val defaultCategories: List<String> =
        registry
            .enabledPoiData()
            .map { it.category }
            .toSet()
            .toList()

    post("/api/pois/on-route", {
        tags = listOf("poi")
        summary = "Slim POIs inside a buffered route corridor (no viewport, no truncation)"
        description =
            "Body: { waypoints: [{lat,lng}…2..$MAX_WAYPOINTS], radius_miles: ${MIN_RADIUS_MILES}..$MAX_RADIUS_MILES, categories? }. " +
            "Returns a GeoJSON FeatureCollection ordered by route_km. " +
            "Backed by RouteCache; the FE typically primes it via /api/route just before this call."
        request {
            body<PoisOnRouteRequestSchema> {
                mediaTypes(ContentType.Application.Json)
                example("Vancouver → Seattle, 30mi corridor, campgrounds") {
                    value =
                        PoisOnRouteRequestSchema(
                            waypoints =
                                listOf(
                                    WaypointSchema(lat = 49.28, lng = -123.10),
                                    WaypointSchema(lat = 47.61, lng = -122.33),
                                ),
                            radius_miles = 30.0,
                            categories = listOf("campground"),
                        )
                }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "FeatureCollection of slim features (+ route_km), sorted by route_km."
                body<String> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed body, bad waypoints, or radius out of range."
                body<String> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Route lookup failed (Mapbox unreachable / cache miss)."
                body<String> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val bodyText = call.receiveText()
        val req =
            try {
                parseOnRouteRequest(bodyText)
            } catch (e: Exception) {
                call.respondText(
                    """{"error":"bad_request","detail":"${escapeJsonInline(e.message ?: "parse failed")}"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@post
            }

        val polylineCoords =
            try {
                val pairs = req.waypoints.map { it.lng to it.lat }
                routeCache.directions(pairs).coordinates
            } catch (e: RoutingException) {
                onRouteLog.warn("on-route lookup failed: {}", e.message)
                call.respondText(
                    """{"error":"routing_unavailable"}""",
                    ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@post
            }
        val corridorLineGeoJson = lineStringGeoJson(polylineCoords)
        val corridorRadiusMeters = req.radiusMiles * MILES_TO_METERS

        val cats = req.categories ?: defaultCategories
        val rows =
            if (cats.isEmpty()) {
                emptyList()
            } else {
                try {
                    fetchOnRoute(ctx, cats, corridorLineGeoJson, corridorRadiusMeters)
                } catch (e: DataAccessException) {
                    val cause = e.cause?.message.orEmpty()
                    if (cause.contains("TopologyException")) {
                        onRouteLog.warn("on-route GEOS topology fault, returning empty: {}", cause)
                        emptyList()
                    } else {
                        throw e
                    }
                }
            }

        call.respondText(
            buildOnRouteFeatureCollection(rows),
            ContentType.Application.Json,
        )
    }
}

private data class OnRouteRequest(
    val waypoints: List<Waypoint>,
    val radiusMiles: Double,
    val categories: List<String>?,
)

private data class Waypoint(
    val lat: Double,
    val lng: Double,
)

private fun parseOnRouteRequest(bodyText: String): OnRouteRequest {
    val root = Json.parseToJsonElement(bodyText).jsonObject

    val wpsArr = root["waypoints"]?.jsonArray ?: error("waypoints required")
    require(wpsArr.size in 2..MAX_WAYPOINTS) {
        "waypoints must have 2..$MAX_WAYPOINTS entries (got ${wpsArr.size})"
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
        root["radius_miles"]?.jsonPrimitive?.doubleOrNull
            ?: error("radius_miles is missing or not a number")
    require(radius in MIN_RADIUS_MILES..MAX_RADIUS_MILES) {
        "radius_miles must be in [$MIN_RADIUS_MILES, $MAX_RADIUS_MILES] (got $radius)"
    }

    val categories =
        root["categories"]
            ?.jsonArray
            ?.mapNotNull {
                it.jsonPrimitive.contentOrNull
                    ?.trim()
                    ?.takeIf { c -> c.isNotEmpty() }
            }?.takeIf { it.isNotEmpty() }

    return OnRouteRequest(waypoints = waypoints, radiusMiles = radius, categories = categories)
}

// Slim per-row shape for /api/pois/on-route. Same id + category +
// lat/lng + subcategory as the bbox endpoint, plus along-route distance
// in km so the FE can sort without re-projecting client-side.
internal data class OnRouteRow(
    val id: Long,
    val category: String,
    val subcategory: String?,
    val lng: Double,
    val lat: Double,
    val routeKm: Double,
)

// Sampling strategy slot for the on-route endpoint. Today we always
// return everything inside the corridor; future variants (even-along-
// route, score-weighted, time-bucketed) plug in here without touching
// the route handler.
internal sealed interface OnRouteSamplingStrategy {
    data object None : OnRouteSamplingStrategy
}

internal fun fetchOnRoute(
    ctx: DSLContext,
    categories: List<String>,
    corridorLineGeoJson: String,
    corridorRadiusMeters: Double,
    strategy: OnRouteSamplingStrategy = OnRouteSamplingStrategy.None,
): List<OnRouteRow> {
    if (categories.isEmpty()) return emptyList()
    val placeholders = categories.joinToString(",") { "?" }

    // Build the corridor LineString once and reuse it for the predicate
    // and the along-route distance projection. ST_LineLocatePoint returns
    // a 0..1 fraction; multiply by ST_Length(::geography) (meters) and
    // divide by 1000 to get km.
    val sql =
        when (strategy) {
            OnRouteSamplingStrategy.None ->
                """
                WITH corridor AS (
                  SELECT
                    ST_SetSRID(ST_GeomFromGeoJSON(?), 4326) AS line,
                    ST_CollectionExtract(
                      ST_MakeValid(
                        ST_Buffer(
                          ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography,
                          ?
                        )::geometry
                      ),
                      3
                    ) AS poly,
                    ST_Length(ST_SetSRID(ST_GeomFromGeoJSON(?), 4326)::geography) / 1000.0 AS len_km
                )
                SELECT id, category, subcategory,
                       ST_X(ST_Centroid(geom)) AS lng,
                       ST_Y(ST_Centroid(geom)) AS lat,
                       ST_LineLocatePoint(corridor.line, ST_Centroid(geom)) * corridor.len_km AS route_km
                FROM pois, corridor
                WHERE deleted_at IS NULL
                  AND category IN ($placeholders)
                  AND ST_Intersects(geom, corridor.poly)
                ORDER BY route_km ASC, id ASC
                """.trimIndent()
        }

    val args = mutableListOf<Any>()
    args.add(corridorLineGeoJson) // corridor.line
    args.add(corridorLineGeoJson) // ST_Buffer input
    args.add(corridorRadiusMeters)
    args.add(corridorLineGeoJson) // len_km
    args.addAll(categories)

    return ctx.fetch(sql, *args.toTypedArray()).map { r ->
        OnRouteRow(
            id = (r.get("id") as Number).toLong(),
            category = r.get("category") as String,
            subcategory = r.get("subcategory") as String?,
            lng = (r.get("lng") as Number).toDouble(),
            lat = (r.get("lat") as Number).toDouble(),
            routeKm = (r.get("route_km") as Number).toDouble(),
        )
    }
}

/**
 * On-route FeatureCollection. Same per-feature shape as the bbox endpoint
 * (id + Point + category[+subcategory]) plus a `route_km` property so the
 * FE can sort or label without re-projecting on the client.
 */
internal fun buildOnRouteFeatureCollection(rows: List<OnRouteRow>): String {
    val sb = StringBuilder()
    sb.append("""{"type":"FeatureCollection","features":[""")
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
        sb.append(""","route_km":""").append(r.routeKm)
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
