package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.model.metadata.Envelope
import ca.floo.roadtrip.model.metadata.ParseResult
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun extractRecgovCampgroundRef(raw: JsonObject): String? =
    raw
        .objectField("connections")
        ?.stringField("ridb_facility_id")
        ?: recgovCampgroundIdFromUrl(raw.stringField("reservation_url"))

internal fun extractRecgovCampsiteRef(reservationUrl: String?): String? = recgovCampsiteIdFromUrl(reservationUrl)

internal fun jsonObjectResults(
    envelopes: List<Envelope>,
    etlSlug: String,
): Sequence<ParseResult<JsonObject>> =
    sequence {
        if (envelopes.isEmpty()) {
            yield(ParseResult.Bad(null, listOf("$etlSlug: no envelopes captured")))
            return@sequence
        }
        for (envelope in envelopes) {
            val sourceId = envelope.part ?: envelope.request.url
            val rows = envelope.payload as? JsonArray
            if (rows == null) {
                yield(ParseResult.Bad(sourceId, listOf("$etlSlug: expected JSON array payload")))
                continue
            }
            for ((index, element) in rows.withIndex()) {
                val row = element as? JsonObject
                if (row == null) {
                    yield(ParseResult.Bad("$sourceId[$index]", listOf("$etlSlug: expected JSON object row")))
                } else {
                    yield(ParseResult.Ok(row))
                }
            }
        }
    }

internal fun JsonObject.stringField(name: String): String? =
    this[name]
        ?.jsonPrimitive
        ?.contentOrNull
        ?.trim()
        ?.takeIf { it.isNotBlank() && it != "null" }

internal fun JsonObject.doubleField(name: String): Double? =
    this[name]
        ?.jsonPrimitive
        ?.doubleOrNull

internal fun JsonObject.intField(name: String): Int? =
    this[name]
        ?.jsonPrimitive
        ?.intOrNull

internal fun JsonObject.booleanField(name: String): Boolean? =
    this[name]
        ?.jsonPrimitive
        ?.booleanOrNull

internal fun JsonObject.objectField(name: String): JsonObject? = this[name] as? JsonObject

internal fun JsonObject.arrayField(name: String): JsonElement? = this[name]?.takeIf { runCatching { it.jsonArray }.isSuccess }

internal fun campflareCampgroundSourceUrl(campflareId: String): String = CampflareUrls.campground(campflareId)

internal fun campgroundLinksWithCampflareSource(
    raw: JsonObject,
    sourceUrl: String,
): JsonElement {
    val links = raw.arrayField(LINKS_FIELD)?.jsonArray.orEmpty()
    return buildJsonArray {
        links.forEach { add(it) }
        if (links.none { link -> (link as? JsonObject)?.sourceLinkUrl() == sourceUrl }) {
            add(
                buildJsonObject {
                    put(TITLE_FIELD, CAMPFLARE_SOURCE_LINK_TITLE)
                    put(URL_FIELD, sourceUrl)
                },
            )
        }
    }
}

private fun JsonObject.sourceLinkUrl(): String? = stringField(URL_FIELD) ?: stringField(HREF_FIELD)

internal fun normalizedLatitude(value: Double?): Double? = normalizedCoordinate(value, LATITUDE_MIN, LATITUDE_MAX)

internal fun normalizedLongitude(value: Double?): Double? = normalizedCoordinate(value, LONGITUDE_MIN, LONGITUDE_MAX)

internal fun normalizedCoordinate(
    value: Double?,
    min: Double,
    max: Double,
): Double? {
    if (value == null) return null
    if (value in min..max) return value
    val scaled = value / E6_COORDINATE_SCALE
    return scaled.takeIf { it in min..max }
}

internal const val CAMPGROUNDS_ETL_SLUG = "campflare-campgrounds"
internal const val CAMPSITES_ETL_SLUG = "campflare-campsites"
internal const val CAMPFLARE_SOURCE_LINK_TITLE = "Campflare source"
internal const val LINKS_FIELD = "links"
internal const val TITLE_FIELD = "title"
internal const val URL_FIELD = "url"
internal const val HREF_FIELD = "href"
internal const val LATITUDE_MIN = -90.0
internal const val LATITUDE_MAX = 90.0
internal const val LONGITUDE_MIN = -180.0
internal const val LONGITUDE_MAX = 180.0
internal const val E6_COORDINATE_SCALE = 1_000_000.0
internal val recgovCampgroundUrlRegex = Regex("""/campgrounds/(\d+)""")
internal val recgovCampsiteUrlRegex = Regex("""/campsites/(\d+)""")

internal fun recgovCampgroundIdFromUrl(url: String?): String? = url?.let { recgovCampgroundUrlRegex.find(it)?.groupValues?.get(1) }

internal fun recgovCampsiteIdFromUrl(url: String?): String? = url?.let { recgovCampsiteUrlRegex.find(it)?.groupValues?.get(1) }
