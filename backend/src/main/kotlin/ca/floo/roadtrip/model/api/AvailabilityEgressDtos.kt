package ca.floo.roadtrip.model.api

import ca.floo.roadtrip.model.availability.AvailabilityStatus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ProviderRefDto(
    @SerialName("data_provider") val dataProvider: String,
    @SerialName("data_provider_ref") val dataProviderRef: String,
)

@Serializable
data class AvailabilityEgressRequestDto(
    val campground: ProviderRefDto,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
)

@Serializable
data class AvailabilityEgressCellDto(
    val campsite: ProviderRefDto,
    val date: String,
    val status: AvailabilityStatus,
    @SerialName("observed_at") val observedAt: String,
)

@Serializable
data class AvailabilityEgressResponseDto(
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
    val cells: List<AvailabilityEgressCellDto>,
)
