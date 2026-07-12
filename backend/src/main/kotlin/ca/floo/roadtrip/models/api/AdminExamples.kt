package ca.floo.roadtrip.models.api

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
                IngestRunListItemSchema(
                    id = 42,
                    target = "campgrounds",
                    kind = "fetch",
                    status = "completed",
                    triggered_by = "admin-api",
                    started_at = "2026-06-06T19:14:02Z",
                    completed_at = "2026-06-06T19:18:31Z",
                ),
                IngestRunListItemSchema(
                    id = 41,
                    target = "planet-fitness",
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
        id = 42,
        target = "campgrounds",
        kind = "fetch",
        status = "completed",
        triggered_by = "admin-api",
        started_at = "2026-06-06T19:14:02Z",
        completed_at = "2026-06-06T19:18:31Z",
        phases =
            listOf(
                IngestRunPhaseSchema(
                    id = 43,
                    phase = "fetch_campgrounds.py",
                    phase_kind = "fetch",
                    status = "completed",
                    started_at = "2026-06-06T19:14:02Z",
                    completed_at = "2026-06-06T19:14:55Z",
                    exit_code = 0,
                ),
                IngestRunPhaseSchema(
                    id = 44,
                    phase = "fetch_bc_parks.py",
                    phase_kind = "fetch",
                    status = "completed",
                    started_at = "2026-06-06T19:14:55Z",
                    completed_at = "2026-06-06T19:15:30Z",
                    exit_code = 0,
                ),
            ),
    )

val EXAMPLE_STATUS =
    StatusResponseSchema(
        targets =
            listOf(
                TargetStatusSchema(
                    target = "campgrounds",
                    last_run = 42,
                    kind = "fetch",
                    status = "completed",
                    age_sec = 3742,
                ),
                TargetStatusSchema(target = "planet-fitness"),
            ),
    )

val EXAMPLE_ERR_NOT_FOUND_BAD_ID = ErrorNotFoundSchema(error = "bad id")

val EXAMPLE_ERR_NOT_FOUND = ErrorNotFoundSchema(error = "not found", id = 99)
