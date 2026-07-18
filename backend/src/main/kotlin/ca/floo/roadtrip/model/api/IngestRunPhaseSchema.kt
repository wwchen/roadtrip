package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class IngestRunPhaseSchema(
    val id: Long,
    val phase: String,
    @SerialName("phase_kind") val phaseKind: String,
    val status: String,
    @SerialName("exit_code") val exitCode: Int? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
    val counts: JsonElement? = null,
    val notes: String? = null,
)
