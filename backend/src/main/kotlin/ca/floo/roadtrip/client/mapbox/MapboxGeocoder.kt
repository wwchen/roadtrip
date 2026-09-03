package ca.floo.roadtrip.client.mapbox

import ca.floo.roadtrip.model.domain.poi.Bbox
import ca.floo.roadtrip.model.routing.GeocodeResult
import ca.floo.roadtrip.support.GeocodeException
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/** `[west, south, east, north]` — the only bbox arity this API speaks. */
private const val BBOX_COORDINATE_COUNT = 4

/**
 * Mapbox Geocoding API v5 forward-geocoding httpClient. Backs /api/geocode for
 * type-as-you-search autofill in the trip planner top bar.
 *
 *   GET https://api.mapbox.com/geocoding/v5/mapbox.places/{q}.json
 *       ?access_token=<token>&autocomplete=true&limit=N[&proximity=lng,lat]
 *   → FeatureCollection where each feature carries `place_name`,
 *     `center: [lng,lat]`, `place_type: ["place"|"address"|"postcode"|...]`
 *
 * Token is the public Mapbox token but we keep it server-side anyway so
 * we can swap providers without touching the frontend.
 */
class MapboxGeocoder(
    private val token: String?,
    private val httpClient: HttpClient = defaultClient(),
    private val baseUrl: String = "https://api.mapbox.com",
) {
    private val log = LoggerFactory.getLogger(MapboxGeocoder::class.java)

    val configured: Boolean get() = !token.isNullOrBlank()

    /**
     * Forward-geocode a free-text query. `proximity` biases results
     * toward a location — useful for "Dallas" returning Dallas TX
     * before Dallas GA when the map is centered on Texas.
     */
    suspend fun forward(
        q: String,
        autocomplete: Boolean = true,
        proximity: String? = null,
        limit: Int = 5,
    ): List<GeocodeResult> {
        val token = this.token ?: throw GeocodeException("roadtrip.mapbox.token not configured")
        val trimmed = q.trim()
        if (trimmed.isBlank()) return emptyList()

        val encoded = trimmed.encodeURLPathPart()
        val limitClamped = limit.coerceIn(1, 10)
        val proxParam = proximity?.let { "&proximity=$it" } ?: ""
        val acParam = if (autocomplete) "true" else "false"
        val url =
            "$baseUrl/geocoding/v5/mapbox.places/$encoded.json" +
                "?access_token=$token&autocomplete=$acParam&limit=$limitClamped$proxParam"

        val response =
            try {
                httpClient.get(url)
            } catch (e: Exception) {
                log.warn("Mapbox geocode network failure: {}", e.message)
                throw GeocodeException("network error: ${e.message}", e)
            }

        if (response.status.value !in 200..299) {
            val body = response.bodyAsText().take(200)
            log.warn("Mapbox geocode HTTP {}: {}", response.status.value, body)
            if (response.status == HttpStatusCode.Unauthorized) {
                throw GeocodeException("Mapbox 401 — token invalid")
            }
            throw GeocodeException("Mapbox HTTP ${response.status.value}")
        }

        return parse(response.bodyAsText())
    }

    private fun parse(body: String): List<GeocodeResult> {
        val root =
            runCatching { Json.parseToJsonElement(body).jsonObject }.getOrElse {
                throw GeocodeException("Mapbox parse failed: ${it.message}")
            }
        val features = root["features"]?.jsonArray ?: return emptyList()
        return features.mapNotNull { f ->
            val obj = f.jsonObject
            val center = obj["center"]?.jsonArray ?: return@mapNotNull null
            val lng = center.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val lat = center.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null
            val name = obj["place_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val placeType =
                obj["place_type"]
                    ?.jsonArray
                    ?.firstOrNull()
                    ?.jsonPrimitive
                    ?.contentOrNull ?: "place"
            val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: ""
            GeocodeResult(
                id = id,
                placeName = name,
                lng = lng,
                lat = lat,
                placeType = placeType,
                bbox = parseBbox(obj["bbox"]),
            )
        }
    }

    /**
     * Mapbox's `bbox`, which it reports for features that HAVE an extent —
     * countries, regions, districts, places, and the parks that come back as
     * `place_type: poi` with a footprint. Address-level features carry none.
     *
     * Anything that is not exactly four finite numbers is treated as absent
     * rather than partially trusted: a half-read extent would frame the map on a
     * box that is not the region, which is worse than falling back to the centre.
     */
    private fun parseBbox(element: JsonElement?): Bbox? {
        val values =
            element
                ?.let { runCatching { it.jsonArray }.getOrNull() }
                ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
                ?: return null
        if (values.size != BBOX_COORDINATE_COUNT) return null
        val (west, south, east, north) = values
        if (west > east || south > north) return null
        return Bbox(west = west, south = south, east = east, north = north)
    }

    companion object {
        fun defaultClient(): HttpClient =
            HttpClient(CIO) {
                engine { requestTimeout = 8_000 }
                defaultRequest {
                    header("User-Agent", "roadtrip-map/1.0 (+https://roadtrip.floo.ca)")
                }
            }
    }
}
