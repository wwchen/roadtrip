package ca.floo.roadtrip.importer

import io.ktor.client.HttpClient
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// In-process port of scripts/fetch_planet_fitness.py.
//
// Hits OpenStreetMap Overpass API for nodes/ways/relations tagged
// brand="Planet Fitness" inside the continental-US bbox, transforms to a
// FeatureCollection of Point features, writes data/planet-fitness.geojson
// with _fetched_at. PlanetFitnessSource (the on-disk reader) is unchanged.
//
// Faithful to the Python script's output shape so the importer's StagedPoi
// stream is byte-equivalent.
class PlanetFitnessHttpSource : HttpFetchSource {
    override val name = "osm-pf"

    override suspend fun fetch(
        client: HttpClient,
        dataDir: File,
    ): FetchOutcome {
        val log = LoggerFactory.getLogger(javaClass)
        log.info("[osm-pf] querying Overpass…")

        val response =
            client.submitForm(
                url = OVERPASS_URL,
                formParameters =
                    parameters {
                        append("data", QUERY)
                    },
            ) {
                headers.append(HttpHeaders.UserAgent, USER_AGENT)
                headers.append(HttpHeaders.Accept, ContentType.Application.Json.toString())
            }
        val payload = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val elements = payload["elements"]?.jsonArray ?: error("Overpass response missing 'elements'")
        log.info("[osm-pf] got {} elements", elements.size)

        val features = buildJsonArray { elements.forEach { transformElement(it.jsonObject)?.let { f -> add(f) } } }

        val out = File(dataDir, "planet-fitness.geojson")
        val fetchedAt = ISO_UTC.format(Instant.now().atOffset(ZoneOffset.UTC))
        val collection =
            buildJsonObject {
                put("_fetched_at", fetchedAt)
                put("type", "FeatureCollection")
                put("features", features)
            }
        out.parentFile.mkdirs()
        out.writeText(collection.toString())
        log.info("[osm-pf] wrote {} features to {}", features.size, out.path)

        return FetchOutcome(featureCount = features.size, outputFile = out)
    }

    private fun transformElement(el: JsonObject): JsonObject? {
        val type = el["type"]?.jsonPrimitive?.contentOrNull ?: return null
        // Nodes carry lat/lon directly; ways/relations have their bbox center
        // under "center" (out:center in the Overpass query).
        val (lon, lat) =
            if (type == "node") {
                val lon = el["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
                val lat = el["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
                lon to lat
            } else {
                val center = el["center"]?.jsonObject ?: return null
                val lon = center["lon"]?.jsonPrimitive?.doubleOrNull ?: return null
                val lat = center["lat"]?.jsonPrimitive?.doubleOrNull ?: return null
                lon to lat
            }
        val tags = el["tags"]?.jsonObject ?: JsonObject(emptyMap())
        val osmId = "$type/${el["id"]?.jsonPrimitive?.contentOrNull ?: ""}"
        val street =
            listOfNotNull(
                tags["addr:housenumber"]?.jsonPrimitive?.contentOrNull,
                tags["addr:street"]?.jsonPrimitive?.contentOrNull,
            ).joinToString(" ")

        return buildJsonObject {
            put("type", "Feature")
            put(
                "geometry",
                buildJsonObject {
                    put("type", "Point")
                    put(
                        "coordinates",
                        buildJsonArray {
                            add(JsonPrimitive(lon))
                            add(JsonPrimitive(lat))
                        },
                    )
                },
            )
            put(
                "properties",
                buildJsonObject {
                    put("osm_id", osmId)
                    put("name", tags["name"]?.jsonPrimitive?.contentOrNull ?: "Planet Fitness")
                    put("street", street)
                    put("city", tags["addr:city"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("state", tags["addr:state"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("postcode", tags["addr:postcode"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("phone", tags["phone"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("website", tags["website"]?.jsonPrimitive?.contentOrNull ?: "")
                    put("opening_hours", tags["opening_hours"]?.jsonPrimitive?.contentOrNull ?: "")
                },
            )
        }
    }

    companion object {
        private const val OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        private const val USER_AGENT = "roadtrip-map/0.1 (personal)"
        private const val BBOX = "24.5,-125.0,49.5,-66.5" // continental US + a bit
        private val QUERY =
            """
            [out:json][timeout:90];
            nwr["brand"="Planet Fitness"]($BBOX);
            out center tags;
            """.trimIndent()
        private val ISO_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
    }
}
