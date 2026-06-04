package ca.floo.roadtrip.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.time.Instant

// Shared base for the hand-curated Alberta-provincial / Parks-Canada JSON
// files. They all share the same shape:
//   { campgrounds: [ { name, lat, lon, park, park_url, season, reservable, ... } ] }
// where any extra source-specific fields (reservable_url, last_verified) are
// preserved into the JSONB properties column.
//
// Each instance owns its own source name; `parks-canada-bc.json` and
// `parks-canada-ab.json` both feed the `parks-canada` source so they share a
// run for sweep semantics.
abstract class CuratedJsonSource(
    private val files: List<File>,
    override val name: String,
) : Source {

    override fun staged(): Sequence<StagedPoi> = sequence {
        for (file in files) {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val arr = root["campgrounds"]!!.jsonArray
            val fetchedAt = Instant.ofEpochMilli(file.lastModified())
            for (item in arr) {
                val obj = item.jsonObject
                val n = obj["name"]?.jsonPrimitive?.content ?: continue
                val lat = obj["lat"]?.jsonPrimitive?.content?.toDouble() ?: continue
                val lon = obj["lon"]?.jsonPrimitive?.content?.toDouble() ?: continue
                val park = obj["park"]?.jsonPrimitive?.content
                val region = regionFor(file)
                val hash8 = stableHash8("$n|${"%.5f".format(lat)}|${"%.5f".format(lon)}")
                // The web layer filter + circle-color match keys off `category`
                // in (federal|provincial|state|local). The curated JSON files
                // don't carry that field — inject it per source so the dots
                // render after the dedup-against-uscampgrounds change.
                val props = if (obj.containsKey("category")) obj else buildJsonObject {
                    obj.entries.forEach { (k, v) -> put(k, v) }
                    put("category", JsonPrimitive(legendCategory))
                }
                yield(
                    StagedPoi(
                        sourceId = "${n.toSlug()}-$hash8",
                        category = Category.CAMPGROUND,
                        name = n,
                        geomGeoJson = pointGeoJson(lon, lat),
                        region = region,
                        unitName = park,
                        properties = props,
                        reserveUrl = obj["reservable_url"]?.contentOrNull()
                            ?: obj["park_url"]?.contentOrNull(),
                        fetchedAt = fetchedAt,
                    )
                )
            }
        }
    }

    // Subclasses derive region from filename (parks-canada-bc.json → BC).
    protected open fun regionFor(file: File): String? = null

    // Legend bucket used by the web filter — federal/provincial/state/local.
    protected abstract val legendCategory: String
}

class AlbertaProvincialSource(file: File) : CuratedJsonSource(listOf(file), "alberta-provincial") {
    override fun regionFor(file: File) = "AB"
    override val legendCategory = "provincial"
}

// Parks Canada has two files (BC + AB). Both feed the same source so the
// import_runs/sweep boundary covers both — re-running with one file missing
// would soft-delete that province's rows.
class ParksCanadaSource(files: List<File>) : CuratedJsonSource(files, "parks-canada") {
    override fun regionFor(file: File): String =
        when {
            file.name.contains("-bc") -> "BC"
            file.name.contains("-ab") -> "AB"
            else -> "??"
        }

    // National parks bucket, same as PC entries previously had in the
    // baked-in geojson — keeps web circle-color federal-green.
    override val legendCategory = "federal"
}
