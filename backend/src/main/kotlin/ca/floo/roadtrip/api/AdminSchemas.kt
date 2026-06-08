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

// Concrete examples surfaced in Swagger UI alongside the schema. Typed
// instances so they share the same field-name contract as the schemas
// above — drift between schema and example is a compile error, not a
// stale doc.

val EXAMPLE_RUN_OUTCOME_COMPLETED_FETCH =
    RunOutcomeSchema(run_id = 42, target = "campgrounds", kind = "fetch", status = "completed")

val EXAMPLE_RUN_OUTCOME_COMPLETED_IMPORT =
    RunOutcomeSchema(run_id = 42, target = "planet-fitness", kind = "import", status = "completed")

val EXAMPLE_RUN_OUTCOME_NOOP =
    RunOutcomeSchema(run_id = 42, target = "parks-canada-curated", kind = "fetch", status = "noop")

val EXAMPLE_RUN_OUTCOME_NOOP_IMPORT =
    RunOutcomeSchema(run_id = 42, target = "tesla-pricing", kind = "import", status = "noop")

val EXAMPLE_RUN_OUTCOME_FAILED =
    RunOutcomeSchema(
        run_id = 42,
        target = "campgrounds",
        kind = "fetch",
        status = "failed",
        failed_phase = "fetch_bc_parks.py",
    )

val EXAMPLE_ERR_UNKNOWN_TARGET =
    ErrorUnknownTargetSchema(
        error = "unknown target",
        target = "nope",
        known = listOf("campgrounds", "national-parks", "planet-fitness", "state-parks", "tesla-index"),
    )

val EXAMPLE_ERR_TARGET_BUSY =
    ErrorTargetBusySchema(error = "target busy", target = "campgrounds", running_run_id = 41)

val EXAMPLE_FAN_OUT_FETCH =
    FanOutResponseSchema(
        kind = "fetch",
        outcomes =
            listOf(
                RunOutcomeSchema(run_id = 1, target = "alberta-provincial", kind = "fetch", status = "noop"),
                RunOutcomeSchema(run_id = 2, target = "campgrounds", kind = "fetch", status = "completed"),
                RunOutcomeSchema(run_id = 3, target = "planet-fitness", kind = "fetch", status = "completed"),
            ),
    )

val EXAMPLE_FAN_OUT_IMPORT =
    FanOutResponseSchema(
        kind = "import",
        outcomes =
            listOf(
                RunOutcomeSchema(run_id = 4, target = "campgrounds", kind = "import", status = "completed"),
                RunOutcomeSchema(run_id = 5, target = "planet-fitness", kind = "import", status = "completed"),
            ),
    )

val EXAMPLE_RUNS_LIST =
    RunsListSchema(
        runs =
            listOf(
                IngestRunSchema(
                    id = 42,
                    target = "campgrounds",
                    phase = "fetch",
                    phase_kind = "target",
                    kind = "fetch",
                    status = "completed",
                    triggered_by = "admin-api",
                    started_at = "2026-06-06T19:14:02Z",
                    completed_at = "2026-06-06T19:18:31Z",
                ),
                IngestRunSchema(
                    id = 41,
                    target = "planet-fitness",
                    phase = "import",
                    phase_kind = "target",
                    kind = "import",
                    status = "completed",
                    triggered_by = "admin-api",
                    started_at = "2026-06-06T19:13:44Z",
                    completed_at = "2026-06-06T19:13:46Z",
                ),
            ),
    )

val EXAMPLE_RUN_DETAIL =
    RunDetailSchema(
        parent =
            IngestRunSchema(
                id = 42,
                target = "campgrounds",
                phase = "fetch",
                phase_kind = "target",
                kind = "fetch",
                status = "completed",
                triggered_by = "admin-api",
                started_at = "2026-06-06T19:14:02Z",
                completed_at = "2026-06-06T19:18:31Z",
            ),
        phases =
            listOf(
                IngestRunSchema(
                    id = 43,
                    target = "campgrounds",
                    phase = "fetch_campgrounds.py",
                    phase_kind = "fetch",
                    parent_run_id = 42,
                    kind = "fetch",
                    status = "completed",
                    triggered_by = "phase",
                    started_at = "2026-06-06T19:14:02Z",
                    completed_at = "2026-06-06T19:14:55Z",
                    exit_code = 0,
                ),
                IngestRunSchema(
                    id = 44,
                    target = "campgrounds",
                    phase = "fetch_bc_parks.py",
                    phase_kind = "fetch",
                    parent_run_id = 42,
                    kind = "fetch",
                    status = "completed",
                    triggered_by = "phase",
                    started_at = "2026-06-06T19:14:55Z",
                    completed_at = "2026-06-06T19:15:30Z",
                    exit_code = 0,
                ),
            ),
    )

val EXAMPLE_HEALTH =
    HealthResponseSchema(
        targets =
            listOf(
                TargetHealthSchema(
                    target = "campgrounds",
                    last_run = 42,
                    kind = "fetch",
                    status = "completed",
                    age_sec = 3742,
                ),
                TargetHealthSchema(target = "planet-fitness"),
            ),
    )

val EXAMPLE_ERR_NOT_FOUND_BAD_ID = ErrorNotFoundSchema(error = "bad id", id = 0)

val EXAMPLE_ERR_NOT_FOUND = ErrorNotFoundSchema(error = "not found", id = 99)
