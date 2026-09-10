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
    /** The row's *declared primary* ref. What the pin books through is the
     *  serving provider's ref, which only the service layer can resolve. */
    val bookingRef: BookingProviderRef?,
    val propertiesJson: String,
    val memberSources: List<String>,
)
