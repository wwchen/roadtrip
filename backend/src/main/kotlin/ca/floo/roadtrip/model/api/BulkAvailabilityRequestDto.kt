package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BulkAvailabilityRequestDto(
    @SerialName("poi_ids") val poiIds: List<Long> = emptyList(),
    @SerialName("start_date") val startDate: String? = null,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("min_nights") val minNights: Int = 1,
    @SerialName("site_type") val siteTypes: List<String> = emptyList(),
)
