package ca.floo.roadtrip.model.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * The area (in square degrees) of the axis-aligned bounding box around every
 * `[lng, lat]` position nested in a GeoJSON Polygon/MultiPolygon `coordinates`
 * array, or null when the array holds no position. Non-finite coordinates are
 * ignored rather than blowing up the bound.
 */
fun boundaryBboxAreaSqDeg(coordinates: JsonArray): Double? {
    var minLng = Double.POSITIVE_INFINITY
    var maxLng = Double.NEGATIVE_INFINITY
    var minLat = Double.POSITIVE_INFINITY
    var maxLat = Double.NEGATIVE_INFINITY
    var found = false

    fun visit(array: JsonArray) {
        val lng = (array.getOrNull(0) as? JsonPrimitive)?.content?.toDoubleOrNull()
        val lat = (array.getOrNull(1) as? JsonPrimitive)?.content?.toDoubleOrNull()
        if (lng != null && lat != null && lng.isFinite() && lat.isFinite()) {
            found = true
            if (lng < minLng) minLng = lng
            if (lng > maxLng) maxLng = lng
            if (lat < minLat) minLat = lat
            if (lat > maxLat) maxLat = lat
        } else {
            array.forEach { element -> if (element is JsonArray) visit(element) }
        }
    }
    coordinates.forEach { element -> if (element is JsonArray) visit(element) }

    return if (found) (maxLng - minLng) * (maxLat - minLat) else null
}

/** The campground search's wire error codes; [wire] is what the route writes into `respondApiError`. */
enum class CampgroundSearchError(
    val wire: String,
) {
    BAD_REQUEST("bad_request"),
    BAD_BOUNDARY("bad_boundary"),
    TOO_MANY_IDS("too_many_ids"),
}

/** The one POI type a campground POI carries; shared so `repo/` never spells the literal. */
const val CAMPGROUND_POI_TYPE = "campground"

/** The search predicate as the repo applies it; mirrors the API's `CampgroundFilterDto`. */
data class CampgroundSearchFilter(
    val siteType: CampsiteKind?,
    val groupSize: Int?,
    val amenities: List<AmenityKey>,
)

data class CampgroundSearchResult(
    /** POI ids, nearest the boundary's centre first. */
    val poiIds: List<Long>,
    /** Campgrounds inside the boundary before the filter. */
    val totalInBoundary: Int,
    /** Campgrounds that pass the filter, before the `max-results` cap. */
    val totalMatching: Int,
    val truncated: Boolean,
)

/** The boundary is not a structurally valid GeoJSON geometry (repo-layer; no jOOQ types). */
class InvalidBoundaryException(
    message: String,
    cause: Throwable,
) : RuntimeException(message, cause)

/** One campground as the bulk summary read returns it. */
data class CampgroundSummaryRow(
    val poiId: Long,
    val lng: Double,
    val lat: Double,
    val campground: Campground,
    val summary: CampgroundSiteSummary?,
)
