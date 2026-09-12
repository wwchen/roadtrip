package ca.floo.roadtrip.service.etl.vendors.aspira

import ca.floo.roadtrip.model.metadata.Envelope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * BC Parks Strapi — paginated JSON pages, rows under `payload.data[]`.
 *
 * The single parser for the feed: [rows] is the full protected-area record the
 * BC ETL enriches campgrounds with, and [points] is the geometry projection of
 * the same rows. Parsed once per instance.
 */
class BcParksStrapiSource(
    private val envelopes: List<Envelope>,
) : GeometrySource {
    private val parsed: List<BcParksStrapiRow> by lazy { parseRows() }

    /** Every protected-area row that carries a name and a point. */
    fun rows(): List<BcParksStrapiRow> = parsed

    override fun points(): Sequence<NamedPoint> = parsed.asSequence().map { NamedPoint(it.name, it.lat, it.lon) }

    private fun parseRows(): List<BcParksStrapiRow> {
        val rows = mutableListOf<BcParksStrapiRow>()
        for (env in envelopes) {
            val data = env.payload.jsonObject["data"]?.jsonArray ?: continue
            for (row in data) {
                val o = row.jsonObject
                val name = o["protectedAreaName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: continue
                val lat = o["latitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                val lon = o["longitude"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull() ?: continue
                rows +=
                    BcParksStrapiRow(
                        name = name,
                        lat = lat,
                        lon = lon,
                        orcs = o["orcs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                        url = o["url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                        description = o.trimmedText("description"),
                        phone = o.trimmedText("parkContact"),
                        photoUrl = extractPhotoUrl(o["parkPhotos"] as? JsonArray),
                    )
            }
        }
        return rows
    }

    private fun JsonObject.trimmedText(key: String): String? =
        this[key]
            ?.jsonPrimitive
            ?.contentOrNull
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    private fun extractPhotoUrl(photos: JsonArray?): String? {
        if (photos == null) return null
        val candidates =
            photos.mapNotNull { raw ->
                val p = raw as? JsonObject ?: return@mapNotNull null
                val url = p["imageUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val isActive = p["isActive"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true
                val isFeatured = p["isFeatured"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                val sortOrder = p["sortOrder"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: Int.MAX_VALUE
                if (!isActive) return@mapNotNull null
                Triple(url, isFeatured, sortOrder)
            }
        return candidates
            .sortedWith(compareByDescending<Triple<String, Boolean, Int>> { it.second }.thenBy { it.third })
            .firstOrNull()
            ?.first
    }
}
