package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AvailabilityResponseDto(
    val provider: String,
    @SerialName("scope_ref") val scopeRef: String? = null,
    @SerialName("campsite_id") val campsiteId: Long? = null,
    @SerialName("longest_run_nights") val longestRunNights: Int? = null,
    @SerialName("checked_at") val checkedAt: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val state: String,
    val season: JsonElement,
    val availability: List<AvailabilityDayDto>,
    val cache: AvailabilityCacheBlock,
)
