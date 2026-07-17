package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class StatusResponseSchema(
    val targets: List<TargetStatusSchema>,
)
