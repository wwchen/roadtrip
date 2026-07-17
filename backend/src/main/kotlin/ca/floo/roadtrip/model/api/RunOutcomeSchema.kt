package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

// Swagger + response DTOs for the admin ingest API.
//
// Field names match the wire format exactly (snake_case for the times,
// kind/status, etc.) so the rendered Swagger doc reflects what the API
// actually returns.

@Serializable
data class RunOutcomeSchema(
    val run_id: Long,
    val target: String,
    val kind: String,
    val status: String,
    val failed_phase: String? = null,
)
