package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityRequestDto(
    val ids: List<Long>,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
)
