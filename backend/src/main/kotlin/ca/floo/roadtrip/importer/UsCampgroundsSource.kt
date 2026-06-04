package ca.floo.roadtrip.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import java.time.Instant

// Normalizes data/campgrounds.geojson (~11.9k features) into StagedPoi rows.
// The upstream `code` field is NOT unique (5697 distinct codes for 11906
// features). Source ID is built from code+state+hash(name|lat|lon) so it is
// stable across re-runs and survives the regex constraint.
class UsCampgroundsSource(
    private val geojson: File,
    private val fetchedAt: Instant = Instant.ofEpochMilli(geojson.lastModified()),
) : Source {

    override val name = "uscampgrounds"

    override fun staged(): Sequence<StagedPoi> = sequence {
        val root = Json.parseToJsonElement(geojson.readText()).jsonObject
        val features = root["features"]!!.jsonArray
        for (feat in features) {
            val obj = feat.jsonObject
            val geom = obj["geometry"]?.jsonObject ?: continue
            if (geom["type"]?.jsonPrimitive?.content != "Point") continue
            val coords = geom["coordinates"]?.jsonArray ?: continue
            val lon = coords[0].jsonPrimitive.content.toDouble()
            val lat = coords[1].jsonPrimitive.content.toDouble()
            val props = obj["properties"]?.jsonObject ?: continue
            val code = props["code"]?.jsonPrimitive?.content ?: continue
            val state = props["state"]?.jsonPrimitive?.content ?: continue
            val name = props["name"]?.jsonPrimitive?.content ?: continue

            yield(
                StagedPoi(
                    sourceId = sourceIdFor(code, state, name, lat, lon),
                    category = Category.CAMPGROUND,
                    name = name,
                    geomWkt = "POINT($lon $lat)",
                    region = state,
                    unitName = props["parent_name"]?.jsonPrimitive?.contentOrNull(),
                    properties = props,
                    reserveUrl = reserveUrlFor(props),
                    fetchedAt = fetchedAt,
                )
            )
        }
    }

    private fun sourceIdFor(code: String, state: String, name: String, lat: Double, lon: Double): String {
        // Hash uses a stable seed so re-runs produce the same id even when
        // upstream re-orders features. lat/lon at 5dp ≈ 1m precision.
        val seed = "$name|${"%.5f".format(lat)}|${"%.5f".format(lon)}"
        val md = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
        val hash8 = md.take(4).joinToString("") { "%02x".format(it) }
        // Some upstream codes contain parens or non-ASCII (PC-BC parks). The
        // pois.source_id CHECK enforces ^[a-z0-9:_-]+$, so collapse anything
        // outside that set to '_'. The hash suffix preserves uniqueness.
        return "${code.toSlug()}-${state.toSlug()}-$hash8"
    }

    private fun String.toSlug(): String =
        lowercase().replace(Regex("[^a-z0-9:_-]+"), "_").trim('_')

    private fun reserveUrlFor(props: JsonObject): String? {
        val recgovId = props["recgov_id"]?.jsonPrimitive?.contentOrNull() ?: return null
        val parentType = props["parent_type"]?.jsonPrimitive?.contentOrNull()
        return when (parentType) {
            "campground" -> "https://www.recreation.gov/camping/campgrounds/$recgovId"
            "recarea" -> "https://www.recreation.gov/camping/gateways/$recgovId"
            else -> "https://www.recreation.gov/camping/campgrounds/$recgovId"
        }
    }
}

private fun JsonElement.contentOrNull(): String? =
    runCatching { jsonPrimitive.content }.getOrNull()
