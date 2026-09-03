package ca.floo.roadtrip.model.domain

import kotlinx.serialization.Serializable

/**
 * Shape of the `campgrounds.location` JSONB column.
 *
 * Coordinates are optional: `pois.geom` is the authoritative geometry, and a
 * row may legitimately carry only region/country/address. Every vendor ETL
 * does supply them, so the drawer still reads them from here.
 */
@Serializable
data class CampgroundLocation(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val region: String? = null,
    val country: String? = null,
    val elevation: Double? = null,
    val directions: String? = null,
    val address: Address? = null,
)
