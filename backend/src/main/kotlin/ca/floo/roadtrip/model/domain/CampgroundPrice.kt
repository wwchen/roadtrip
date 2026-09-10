package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/** The `campgrounds.price` JSONB object: the vendor's nightly range. */
@Serializable
data class CampgroundPrice(
    val minimum: Double? = null,
    val maximum: Double? = null,
    val currency: String? = null,
)
