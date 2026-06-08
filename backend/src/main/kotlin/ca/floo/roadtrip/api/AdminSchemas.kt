package ca.floo.roadtrip.api

import kotlinx.serialization.Serializable

// Swagger schemas for the admin ingest API. These are *only* read by the
// OpenAPI spec generator — actual responses are still hand-built JSON
// strings (the hot paths stream out of jOOQ ResultSets, and we don't want
// to re-shape every row into an instance).
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

@Serializable
data class FanOutResponseSchema(
    val kind: String,
    val outcomes: List<RunOutcomeSchema>,
)

@Serializable
data class IngestRunSchema(
    val id: Long,
    val target: String,
    val phase: String,
    val phase_kind: String,
    val parent_run_id: Long? = null,
    val kind: String,
    val status: String,
    val triggered_by: String,
    val started_at: String,
    val completed_at: String? = null,
    val exit_code: Int? = null,
)

@Serializable
data class RunsListSchema(
    val runs: List<IngestRunSchema>,
)

@Serializable
data class RunDetailSchema(
    val parent: IngestRunSchema,
    val phases: List<IngestRunSchema>,
)

@Serializable
data class TargetHealthSchema(
    val target: String,
    val last_run: Long? = null,
    val kind: String? = null,
    val status: String? = null,
    val age_sec: Long? = null,
)

@Serializable
data class HealthResponseSchema(
    val targets: List<TargetHealthSchema>,
)

@Serializable
data class ErrorUnknownTargetSchema(
    val error: String,
    val target: String,
    val known: List<String>,
)

@Serializable
data class ErrorTargetBusySchema(
    val error: String,
    val target: String,
    val running_run_id: Long,
)

@Serializable
data class ErrorNotFoundSchema(
    val error: String,
    val id: Long,
)

// /api/pois request body — bbox is required (4 numbers, [w,s,e,n]),
// zoom + categories + polygon optional. Polygon is a GeoJSON Polygon
// whose outer ring has at least 4 points.
@Serializable
data class PoisRequestSchema(
    val bbox: List<Double>,
    val zoom: Int? = null,
    val categories: List<String>? = null,
    val polygon: GeoJsonPolygonSchema? = null,
)

@Serializable
data class GeoJsonPolygonSchema(
    val type: String,
    val coordinates: List<List<List<Double>>>,
)
