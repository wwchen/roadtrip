package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityPollersSummary(
    val active: Int,
    val dormant: Int,
    @SerialName("due_now") val dueNow: Int,
    val claimed: Int,
)
