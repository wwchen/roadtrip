package ca.floo.roadtrip.model.domain

/** Per-campground campsite aggregates, as `campground_site_summary` stores them. */
data class CampgroundSiteSummary(
    val siteTotal: Int,
    /** Live campsites per kind. */
    val siteCounts: Map<CampsiteKind, Int>,
    /** The largest campsite `max_people`, or null when no site carries one. */
    val maxPeople: Int?,
)
