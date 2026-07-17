package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** 429 response for `POST /api/availability/pollers/{id}/force`: still cooling down. */
@Serializable
data class CheckNowCooldownDto(
    @SerialName("poller_id") val pollerId: Long,
    @SerialName("retry_after_sec") val retryAfterSec: Long,
)
