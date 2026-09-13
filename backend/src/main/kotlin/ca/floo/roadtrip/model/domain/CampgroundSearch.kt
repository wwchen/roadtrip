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
    val truncated: Boolean,
)

/** One campground as the bulk summary read returns it. */
data class CampgroundSummaryRow(
    val poiId: Long,
    val lng: Double,
    val lat: Double,
    val campground: Campground,
    val summary: CampgroundSiteSummary?,
)
