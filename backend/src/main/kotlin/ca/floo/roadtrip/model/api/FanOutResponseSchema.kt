package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class FanOutResponseSchema(
    val kind: String,
    val outcomes: List<RunOutcomeSchema>,
)
