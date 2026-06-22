package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityResponseDto(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val results: List<BulkAvailabilityEntryDto>,
)
