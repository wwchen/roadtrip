package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class ErrorUnknownTargetSchema(
    val error: String,
    val target: String,
    val known: List<String>,
)
