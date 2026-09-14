package ca.floo.roadtrip.model.domain

/** The search predicate as the repo applies it; mirrors the API's `CampgroundFilterDto`. */
data class CampgroundSearchFilter(
    val siteType: String?,
    val groupSize: Int?,
    val amenities: List<String>,
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
