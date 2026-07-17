package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class ErrorTargetBusySchema(
    val error: String,
    val target: String,
    val running_run_id: Long,
)
