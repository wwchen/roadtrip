package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 200 response for `POST /api/availability/pollers/{id}/force`: the poller was pulled due. */
@Serializable
data class CheckNowResponseDto(
    @SerialName("poller_id") val pollerId: Long,
    @SerialName("next_run_at") val nextRunAt: String,
)
