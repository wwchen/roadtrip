package ca.floo.roadtrip.service.etl.vendors.campflare

import ca.floo.roadtrip.models.domain.CatalogVendorRefUpsertCandidate
import ca.floo.roadtrip.models.metadata.Envelope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal fun recgovCampgroundVendorRef(
    raw: JsonObject,
    campflareId: String,
): CatalogVendorRefUpsertCandidate? {
    val recgovId =
        raw
            .objectField("connections")
            ?.stringField("ridb_facility_id")
            ?: recgovCampgroundIdFromUrl(raw.stringField("reservation_url"))
            ?: return null
    return CatalogVendorRefUpsertCandidate(
        vendor = RECGOV_CAMPGROUND_VENDOR,
        vendorRefId = "$RECGOV_CAMPGROUND_REF_PREFIX$recgovId",
        sourceUrl = raw.stringField("reservation_url"),
        payload =
            buildJsonObject {
                put("recgov_id", recgovId)
                put("campflare_id", campflareId)
            },
    )
}

internal fun recgovCampsiteVendorRef(
    raw: JsonObject,
    campflareId: String,
    reservationUrl: String?,
): CatalogVendorRefUpsertCandidate? {
    val recgovId = recgovCampsiteIdFromUrl(reservationUrl) ?: return null
    return CatalogVendorRefUpsertCandidate(
        vendor = RECGOV_CAMPSITE_VENDOR,
        vendorRefId = recgovId,
        sourceUrl = reservationUrl,
        payload =
            buildJsonObject {
                put("recgov_id", recgovId)
                put("campflare_id", campflareId)
                raw.stringField("campground_id")?.let { put("campflare_campground_id", it) }
            },
    )
}

internal fun jsonObjects(envelopes: List<Envelope>): List<JsonObject> =
    envelopes.flatMap { envelope ->
        envelope.payload.jsonArray.mapNotNull { it as? JsonObject }
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

internal const val CAMPFLARE_VENDOR = "campflare"
internal const val RECGOV_CAMPGROUND_VENDOR = "recgov"
internal const val RECGOV_CAMPSITE_VENDOR = "recgov"
internal const val RECGOV_CAMPGROUND_REF_PREFIX = "recgov-"
internal const val CAMPGROUNDS_ETL_SLUG = "campflare-campgrounds"
internal const val CAMPSITES_ETL_SLUG = "campflare-campsites"
internal const val CAMPGROUND_API_URL = "https://api.campflare.com/v2/campground"
internal const val LATITUDE_MIN = -90.0
internal const val LATITUDE_MAX = 90.0
internal const val LONGITUDE_MIN = -180.0
internal const val LONGITUDE_MAX = 180.0
internal const val E6_COORDINATE_SCALE = 1_000_000.0
internal val RECGOV_CAMPGROUND_URL = Regex("""/campgrounds/(\d+)""")
internal val RECGOV_CAMPSITE_URL = Regex("""/campsites/(\d+)""")

internal fun recgovCampgroundIdFromUrl(url: String?): String? = url?.let { RECGOV_CAMPGROUND_URL.find(it)?.groupValues?.get(1) }

internal fun recgovCampsiteIdFromUrl(url: String?): String? = url?.let { RECGOV_CAMPSITE_URL.find(it)?.groupValues?.get(1) }
