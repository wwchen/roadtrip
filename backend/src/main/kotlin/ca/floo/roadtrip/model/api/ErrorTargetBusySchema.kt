package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ErrorTargetBusySchema(
    val error: String,
    val target: String,
    @SerialName("running_run_id") val runningRunId: Long,
)
