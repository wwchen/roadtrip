package ca.floo.roadtrip.model.domain.poi

import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef

/**
 * Campground-owned projection for hydrating GET /api/pois/{id}.
 */
data class CampgroundPoiDetail(
    val campground: Campground,
    val source: String,
    val sourceId: String,
    val bookingRef: BookingProviderRef?,
    val propertiesJson: String,
    val memberSources: List<String>,
)
