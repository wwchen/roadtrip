package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class IngestRunPhaseSchema(
    val id: Long,
    val phase: String,
    val phase_kind: String,
    val status: String,
    val exit_code: Int? = null,
    val started_at: String,
    val completed_at: String? = null,
    val counts: JsonElement? = null,
    val notes: String? = null,
)
