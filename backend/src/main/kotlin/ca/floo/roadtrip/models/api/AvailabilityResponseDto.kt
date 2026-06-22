package ca.floo.roadtrip.models.api

import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AvailabilityResponseDto(
    val provider: String,
    @SerialName("campground_id") val campgroundId: String? = null,
    val host: String? = null,
    @SerialName("map_id") val mapId: String? = null,
    @SerialName("reservable_id") val reservableId: String? = null,
    @SerialName("checked_at") val checkedAt: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val state: String,
    val season: JsonElement,
    val availability: List<AvailabilityDayDto>,
    val cache: AvailabilityCacheBlock,
)
