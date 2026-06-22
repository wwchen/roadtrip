package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityEntryDto(
    val id: Long,
    val status: Int,
    @SerialName("available_dates") val availableDates: List<String>,
)
