package ca.floo.roadtrip.route

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Thin client around the Mapbox Directions API. v1 only handles driving
 * profile + GeoJSON geometry.
 *
 * Verified shape (2026-06-06):
 *   GET https://api.mapbox.com/directions/v5/mapbox/driving/{lng,lat;lng,lat;...}
 *       ?geometries=geojson&overview=full&access_token=<token>
 *   200 → { code: "Ok", routes: [ { geometry: LineString, distance, duration, legs: [...] } ] }
 *
 * Mapbox accepts up to 25 waypoints. Coords are in the URL path,
 * semicolon-separated. Token is a query param. Free tier: 100k req/mo.
 */
class MapboxDirections(
    private val token: String?,
    private val client: HttpClient = defaultClient(),
    private val baseUrl: String = "https://api.mapbox.com",
) {
    private val log = LoggerFactory.getLogger(MapboxDirections::class.java)

    val configured: Boolean get() = !token.isNullOrBlank()

    /**
     * Fetch a driving route through N waypoints (>= 2, <= 25). Throws
     * [RoutingException] for any failure. Caller decides how to map to HTTP
     * status codes.
     */
    suspend fun directions(coords: List<Pair<Double, Double>>): RouteResponse {
        require(coords.size in 2..25) {
            "directions: need 2..25 waypoints, got ${coords.size}"
        }
        val token =
            this.token
                ?: throw RoutingException("MAPBOX_TOKEN not configured")

        val coordPath =
            coords
                .joinToString(";") { (lng, lat) -> "$lng,$lat" }
                .encodeURLPathPart()
        val url =
            "$baseUrl/directions/v5/mapbox/driving/$coordPath" +
                "?geometries=geojson&overview=full&access_token=$token"

        val response =
            try {
                client.get(url)
            } catch (e: Exception) {
                log.warn("Mapbox network failure: {}", e.message)
                throw RoutingException("network error: ${e.message}", e)
            }

        val body = response.bodyAsText()
        if (response.status.value !in 200..299) {
            log.warn("Mapbox HTTP {}: {}", response.status.value, body.take(300))
            throw RoutingException("Mapbox HTTP ${response.status.value}")
        }

        val root =
            runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw RoutingException("Mapbox response parse failed: ${it.message}")
            }
        val code = root["code"]?.jsonPrimitive?.contentOrNull
        if (code != "Ok") {
            // Mapbox returns code:"NoRoute" / "InvalidInput" / etc. with
            // 200 status. Bubble those up as a routing failure.
            val msg = root["message"]?.jsonPrimitive?.contentOrNull ?: code ?: "unknown"
            throw RoutingException("Mapbox code=$code: $msg")
        }

        val routes = root["routes"]?.jsonArray.orEmpty()
        if (routes.isEmpty()) throw RoutingException("Mapbox response: no routes")
        val r = routes[0].jsonObject

        val geom = r["geometry"]?.jsonObject ?: throw RoutingException("no geometry")
        if (geom["type"]?.jsonPrimitive?.contentOrNull != "LineString") {
            throw RoutingException("expected LineString, got ${geom["type"]}")
        }
        val coordsArr = geom["coordinates"]?.jsonArray ?: throw RoutingException("no coordinates")
        val outCoords =
            coordsArr.map { c ->
                val pair = c.jsonArray
                listOf(
                    pair[0].jsonPrimitive.doubleOrNull ?: throw RoutingException("bad lng"),
                    pair[1].jsonPrimitive.doubleOrNull ?: throw RoutingException("bad lat"),
                )
            }
        if (outCoords.size < 2) throw RoutingException("degenerate route: ${outCoords.size} coords")

        val distance = r["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0
        val duration = r["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0

        val legs =
            r["legs"]?.jsonArray.orEmpty().map { leg ->
                val lo = leg.jsonObject
                RouteLeg(
                    distanceMeters = lo["distance"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                    durationSeconds = lo["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                )
            }

        return RouteResponse(
            coordinates = outCoords,
            distanceMeters = distance,
            durationSeconds = duration,
            legs = legs,
        )
    }

    companion object {
        fun defaultClient(): HttpClient =
            HttpClient(CIO) {
                engine { requestTimeout = 15_000 }
                defaultRequest {
                    header("User-Agent", "roadtrip-map/1.0 (+https://roadtrip.floo.ca)")
                }
            }
    }
}

/** Driving route. coordinates is `[[lng,lat], [lng,lat], ...]` GeoJSON-style. */
data class RouteResponse(
    val coordinates: List<List<Double>>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val legs: List<RouteLeg>,
)

/** Per-leg summary (one entry per segment between adjacent waypoints). */
data class RouteLeg(
    val distanceMeters: Double,
    val durationSeconds: Double,
)

/** Any routing failure. Caller maps to HTTP. */
class RoutingException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
