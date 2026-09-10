package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** One entry of the `campgrounds.amenities` JSONB array. */
@Serializable
data class CampgroundAmenity(
    val key: AmenityKey,
    val present: Boolean = true,
    // TOILETS carries the vendor's toilet kind ("vault"); OTHER carries the vendor label verbatim.
    val detail: String? = null,
)
