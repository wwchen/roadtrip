package ca.floo.roadtrip.models.api

import kotlinx.serialization.Serializable

@Serializable
data class FanOutResponseSchema(
    val kind: String,
    val outcomes: List<RunOutcomeSchema>,
)
