package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class StatusResponseSchema(
    val targets: List<TargetStatusSchema>,
)
