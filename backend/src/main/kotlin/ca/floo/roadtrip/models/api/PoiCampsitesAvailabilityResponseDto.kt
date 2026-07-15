package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PoiCampsitesAvailabilityResponseDto(
    @SerialName("poi_id") val poiId: Long,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    @SerialName("watch_capabilities") val watchCapabilities: AvailabilityWatchCapabilitiesDto,
    val campsites: List<AvailabilityResponseDto>,
)
