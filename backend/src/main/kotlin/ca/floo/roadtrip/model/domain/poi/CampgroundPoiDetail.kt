package ca.floo.roadtrip.model.domain.poi

import ca.floo.roadtrip.model.domain.Campground

/**
 * Campground-owned projection for hydrating GET /api/pois/{id}.
 *
 * No booking ref of its own: what a pin books through is the resolved booking
 * identity, which only the service layer can decide, and the row's declared
 * refs already travel on [campground].
 */
data class CampgroundPoiDetail(
    val campground: Campground,
    val source: String,
    val sourceId: String,
    val propertiesJson: String,
    val memberSources: List<String>,
)
