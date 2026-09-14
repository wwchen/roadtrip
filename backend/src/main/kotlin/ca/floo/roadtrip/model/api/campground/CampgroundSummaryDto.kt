package ca.floo.roadtrip.model.api.campground

import ca.floo.roadtrip.model.api.poi.AmenityDto
import ca.floo.roadtrip.model.api.poi.RatingDto
import ca.floo.roadtrip.model.domain.CampsiteKind
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the in-view campground list renders and filters on. A slim cousin of
 * `GET /api/pois/{id}`: same field names where both carry a field, none of the
 * detail's raw payloads.
 */
@Serializable
data class CampgroundSummaryDto(
    /** POI id, the identity cards, pins and the drawer share. */
    val id: Long,
    @SerialName("campground_id") val campgroundId: Long,
    val name: String,
    val region: String? = null,
    val agency: String? = null,
    val lng: Double,
    val lat: Double,
    val rating: RatingDto? = null,
    @SerialName("availability_supported") val availabilitySupported: Boolean,
    @SerialName("booking_system") val bookingSystem: String? = null,
    val amenities: List<AmenityDto>,
    /** Live campsites per kind. */
    @SerialName("site_counts") val siteCounts: Map<CampsiteKind, Int>,
    @SerialName("site_total") val siteTotal: Int,
    /** The largest campsite `max_people`; absent when no site carries one. */
    @SerialName("max_people") val maxPeople: Int? = null,
)
