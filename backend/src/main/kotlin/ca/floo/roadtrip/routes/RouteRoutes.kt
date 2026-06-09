package ca.floo.roadtrip.routes

import ca.floo.roadtrip.client.RoutingException
import ca.floo.roadtrip.repo.RouteCache
import ca.floo.roadtrip.repo.RouteCorridorRepo
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jooq.DSLContext
import org.jooq.exception.DataAccessException

/**
 * GET /api/route?coords=lng,lat;lng,lat;...
 *
 * Backend proxy for Mapbox Directions API. Token stays server-side.
 *
 * Returns:
 *   200 { type:"FeatureCollection", features: [ LineString feature with
 *         distance_m, duration_s, legs[] in properties ] }
 *   400 for malformed coords / wrong number of waypoints
 *   503 when MAPBOX_TOKEN unset or upstream fails
 */
fun Route.routeRoutes(
    routeCache: RouteCache,
    ctx: DSLContext,
) {
    val routeCorridorRepo = RouteCorridorRepo(ctx)

    get("/api/route") {
        if (!routeCache.configured) {
            call.respondText(
                """{"error":"routing_unavailable","detail":"MAPBOX_TOKEN not set"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val raw = call.request.queryParameters["coords"].orEmpty()
        val pieces = raw.split(";").map { it.trim() }.filter { it.isNotEmpty() }

        if (pieces.size < 2) {
            call.respondText(
                """{"error":"too_few_points","detail":"need >= 2 waypoints in coords=lng,lat;lng,lat[;...]"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }
        if (pieces.size > MAX_ROUTE_WAYPOINTS) {
            call.respondText(
                """{"error":"too_many_points","detail":"max $MAX_ROUTE_WAYPOINTS waypoints"}""",
                io.ktor.http.ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        val coords = mutableListOf<Pair<Double, Double>>()
        for ((i, p) in pieces.withIndex()) {
            val parts = p.split(",")
            if (parts.size != 2) {
                call.respondText(
                    """{"error":"bad_coords","detail":"point $i: '$p' is not 'lng,lat'"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@get
            }
            val lng = parts[0].toDoubleOrNull()
            val lat = parts[1].toDoubleOrNull()
            if (lng == null || lat == null) {
                call.respondText(
                    """{"error":"bad_coords","detail":"point $i: '$p' is not 'lng,lat'"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@get
            }
            if (lng !in -180.0..180.0 || lat !in -90.0..90.0) {
                call.respondText(
                    """{"error":"out_of_range","detail":"point $i out of lng/lat range"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@get
            }
            coords.add(lng to lat)
        }
        val corridorRadiusMiles =
            call.request.queryParameters["radius_miles"]?.let { rawRadius ->
                val radius =
                    rawRadius.toDoubleOrNull()
                        ?: return@get call.respondText(
                            """{"error":"bad_radius","detail":"radius_miles must be a number"}""",
                            io.ktor.http.ContentType.Application.Json,
                            HttpStatusCode.BadRequest,
                        )
                if (radius !in MIN_ROUTE_CORRIDOR_RADIUS_MILES..MAX_ROUTE_CORRIDOR_RADIUS_MILES) {
                    return@get call.respondText(
                        """{"error":"bad_radius","detail":"radius_miles must be in [$MIN_ROUTE_CORRIDOR_RADIUS_MILES, $MAX_ROUTE_CORRIDOR_RADIUS_MILES]"}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.BadRequest,
                    )
                }
                radius
            }
        // Mapbox rejects identical adjacent waypoints with code:"InvalidInput".
        // Catch it before the round-trip.
        for (i in 1 until coords.size) {
            if (coords[i] == coords[i - 1]) {
                call.respondText(
                    """{"error":"duplicate_adjacent","detail":"points $i and ${i - 1} are identical"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.BadRequest,
                )
                return@get
            }
        }

        val response =
            try {
                routeCache.directions(coords)
            } catch (e: RoutingException) {
                call.respondText(
                    """{"error":"routing_unavailable","detail":"${escapeJson(e.message ?: "")}"}""",
                    io.ktor.http.ContentType.Application.Json,
                    HttpStatusCode.ServiceUnavailable,
                )
                return@get
            }

        val routeLineGeoJson = lineStringGeoJson(response.coordinates)
        val corridorPolygonGeoJson =
            corridorRadiusMiles?.let { radiusMiles ->
                try {
                    routeCorridorRepo.bufferedPolygonGeoJson(
                        routeLineGeoJson,
                        routeCorridorRadiusMeters(radiusMiles),
                    )
                } catch (e: DataAccessException) {
                    call.respondText(
                        """{"error":"corridor_unavailable","detail":"${escapeJson(e.message ?: "")}"}""",
                        io.ktor.http.ContentType.Application.Json,
                        HttpStatusCode.ServiceUnavailable,
                    )
                    return@get
                }
            }

        val json =
            buildJsonObject {
                put("type", "FeatureCollection")
                put(
                    "features",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "Feature")
                                put(
                                    "geometry",
                                    buildJsonObject {
                                        put("type", "LineString")
                                        put(
                                            "coordinates",
                                            buildJsonArray {
                                                for (c in response.coordinates) {
                                                    add(buildJsonArray { c.forEach { add(it) } })
                                                }
                                            },
                                        )
                                    },
                                )
                                put(
                                    "properties",
                                    buildJsonObject {
                                        put("distance_m", response.distanceMeters)
                                        put("duration_s", response.durationSeconds)
                                        put(
                                            "legs",
                                            buildJsonArray {
                                                for (leg in response.legs) {
                                                    add(
                                                        buildJsonObject {
                                                            put("distance_m", leg.distanceMeters)
                                                            put("duration_s", leg.durationSeconds)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                        // Echo back the input waypoints so the caller knows
                                        // exactly what was routed. Useful for debugging.
                                        put(
                                            "waypoints",
                                            buildJsonArray {
                                                for ((lng, lat) in coords) {
                                                    add(
                                                        buildJsonArray {
                                                            add(lng)
                                                            add(lat)
                                                        },
                                                    )
                                                }
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        if (corridorRadiusMiles != null && corridorPolygonGeoJson != null) {
                            add(
                                buildJsonObject {
                                    put("type", "Feature")
                                    put("geometry", Json.parseToJsonElement(corridorPolygonGeoJson))
                                    put(
                                        "properties",
                                        buildJsonObject {
                                            put("role", "corridor")
                                            put("radius_miles", corridorRadiusMiles)
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
            }
        call.respondText(json.toString(), io.ktor.http.ContentType.Application.Json)
    }
}

private fun escapeJson(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
