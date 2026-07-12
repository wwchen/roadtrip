package ca.floo.roadtrip.models.domain.poi

import ca.floo.roadtrip.models.domain.Campground

/**
 * Campground-owned projection for hydrating GET /api/pois/{id}.
 */
data class CampgroundPoiDetail(
    val campground: Campground,
    val source: String,
    val sourceId: String,
    val providerRefJson: String?,
    val ctaProviderRefJson: String?,
    val propertiesJson: String,
    val memberSources: List<String>,
)
