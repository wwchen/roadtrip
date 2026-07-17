package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class IngestRunListItemSchema(
    val id: Long,
    val target: String,
    val kind: String,
    val status: String,
    val triggered_by: String,
    val started_at: String,
    val completed_at: String? = null,
)
