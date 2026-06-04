package ca.floo.roadtrip.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
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

    override fun staged(): Sequence<StagedPoi> =
        sequence {
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

                // The upstream campgrounds.geojson has a long-tail of Canadian
                // entries (codes prefixed PC-AB / PC-BC / parks-canada-bc-) that
                // were merged in by an old enrichment pass. Those duplicate the
                // hand-curated parks-canada source — same lat/lng, no
                // `last_verified`, and they outrank the curated rows in search
                // because they appear first in the index. Skip them so
                // parks-canada is the single source of truth for Canadian parks.
                if (code.startsWith("PC-AB") ||
                    code.startsWith("PC-BC") ||
                    code.startsWith("parks-canada-")
                ) {
                    continue
                }

                yield(
                    StagedPoi(
                        sourceId = sourceIdFor(code, state, name, lat, lon),
                        category = Category.CAMPGROUND,
                        name = name,
                        geomGeoJson = pointGeoJson(lon, lat),
                        region = state,
                        unitName = props["parent_name"]?.jsonPrimitive?.contentOrNull(),
                        properties = props,
                        reserveUrl = reserveUrlFor(props),
                        fetchedAt = fetchedAt,
                    ),
                )
            }
        }

    private fun sourceIdFor(
        code: String,
        state: String,
        name: String,
        lat: Double,
        lon: Double,
    ): String {
        // Hash uses a stable seed so re-runs produce the same id even when
        // upstream re-orders features. lat/lon at 5dp ≈ 1m precision.
        val hash8 = stableHash8("$name|${"%.5f".format(lat)}|${"%.5f".format(lon)}")
        // Some upstream codes contain parens or non-ASCII (PC-BC parks). The
        // pois.source_id CHECK enforces ^[a-z0-9:_-]+$, so collapse anything
        // outside that set to '_'. The hash suffix preserves uniqueness.
        return "${code.toSlug()}-${state.toSlug()}-$hash8"
    }

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
