package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityResponseDto(
    val pois: List<BulkPoiAvailabilityDto>,
)

/**
 * One requested POI. Exactly one of [campsites] and [error] is set: an empty
 * [campsites] list means the POI resolved but no site met `min_nights`.
 */
@Serializable
data class BulkPoiAvailabilityDto(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    val campsites: List<AvailabilityResponseDto>? = null,
    val error: String? = null,
)
