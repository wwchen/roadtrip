package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PoiReservablesAvailabilityResponseDto(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val reservables: List<AvailabilityResponseDto>,
)
