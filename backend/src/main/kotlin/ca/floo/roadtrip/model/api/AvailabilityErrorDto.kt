package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityErrorDto(
    val state: String = "error",
    val error: String,
    @SerialName("upstream_status") val upstreamStatus: Int? = null,
    @SerialName("earliest_date") val earliestDate: String? = null,
    @SerialName("time_zone") val timeZone: String? = null,
    @SerialName("latest_date") val latestDate: String? = null,
    @SerialName("max_days") val maxDays: Int? = null,
)
