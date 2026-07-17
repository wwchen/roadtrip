package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class RunDetailSchema(
    val id: Long,
    val target: String,
    val kind: String,
    val status: String,
    val triggered_by: String,
    val started_at: String,
    val completed_at: String? = null,
    val notes: String? = null,
    val phases: List<IngestRunPhaseSchema>,
)
