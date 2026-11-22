package ca.floo.roadtrip.client.mapbox

import ca.floo.roadtrip.model.routing.RouteLeg
import ca.floo.roadtrip.model.routing.RouteResponse
import ca.floo.roadtrip.support.RoutingException
import ca.floo.roadtrip.support.RoutingFailure
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.encodeURLPathPart
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

private const val MAPBOX_OK = "Ok"
private const val MAPBOX_NO_ROUTE = "NoRoute"
private const val MAPBOX_NO_SEGMENT = "NoSegment"
private const val MAPBOX_INVALID_INPUT = "InvalidInput"
private const val MAPBOX_PROFILE_NOT_FOUND = "ProfileNotFound"
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_UNPROCESSABLE = 422
private const val HTTP_TOO_MANY_REQUESTS = 429
private const val HTTP_SUCCESS_RANGE_START = 200
private const val HTTP_SUCCESS_RANGE_END = 299
private const val LOGGED_BODY_CHARS = 300

private val codeFailures =
    mapOf(
        MAPBOX_NO_ROUTE to RoutingFailure.NO_ROUTE,
        MAPBOX_NO_SEGMENT to RoutingFailure.NO_SEGMENT,
        MAPBOX_INVALID_INPUT to RoutingFailure.INVALID_INPUT,
        MAPBOX_PROFILE_NOT_FOUND to RoutingFailure.INVALID_INPUT,
    )

/**
 * Thin httpClient around the Mapbox Directions API. v1 only handles driving
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
    private val httpClient: HttpClient = defaultClient(),
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
                ?: throw RoutingException("roadtrip.mapbox.token not configured", failure = RoutingFailure.NOT_CONFIGURED)

        val coordPath =
            coords
                .joinToString(";") { (lng, lat) -> "$lng,$lat" }
                .encodeURLPathPart()
        val url =
            "$baseUrl/directions/v5/mapbox/driving/$coordPath" +
                "?geometries=geojson&overview=full&access_token=$token"

        val response =
            try {
                httpClient.get(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("Mapbox network failure", e)
                throw RoutingException("network error: ${e::class.simpleName}: ${e.message}", e, RoutingFailure.UNREACHABLE)
            }

        val status = response.status.value
        val body = response.bodyAsText()
        val parsed =
            try {
                Json.parseToJsonElement(body).jsonObject
            } catch (e: IllegalArgumentException) {
                if (status in HTTP_SUCCESS_RANGE_START..HTTP_SUCCESS_RANGE_END) {
                    throw RoutingException("Mapbox response parse failed: ${e.message}", e)
                }
                null
            }
        val code = parsed?.stringField("code")
        val message = parsed?.stringField("message")
        if (status !in HTTP_SUCCESS_RANGE_START..HTTP_SUCCESS_RANGE_END) {
            log.warn("Mapbox HTTP {}: {}", status, body.take(LOGGED_BODY_CHARS))
            throw RoutingException(
                "Mapbox HTTP $status code=$code: ${message ?: body.take(LOGGED_BODY_CHARS)}",
                failure = httpFailure(status, code),
            )
        }
        val root = checkNotNull(parsed)
        if (code != MAPBOX_OK) {
            throw RoutingException(
                "Mapbox code=$code: ${message ?: code}",
                failure = code?.let(codeFailures::get) ?: RoutingFailure.UPSTREAM_ERROR,
            )
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

    private fun httpFailure(
        status: Int,
        code: String?,
    ): RoutingFailure =
        when (status) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> RoutingFailure.UPSTREAM_AUTH
            HTTP_TOO_MANY_REQUESTS -> RoutingFailure.UPSTREAM_RATE_LIMITED
            else ->
                code?.let(codeFailures::get)
                    ?: if (status == HTTP_UNPROCESSABLE) RoutingFailure.INVALID_INPUT else RoutingFailure.UPSTREAM_ERROR
        }

    private fun JsonObject.stringField(name: String): String? = this[name]?.jsonPrimitive?.contentOrNull

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
