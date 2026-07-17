package ca.floo.roadtrip.models.api

// Concrete examples surfaced in Swagger UI alongside the schema. Typed
// instances so they share the same field-name contract as the schemas
// above — drift between schema and example is a compile error, not a
// stale doc.

val EXAMPLE_RUN_OUTCOME_COMPLETED_IMPORT =
    RunOutcomeSchema(run_id = 42, target = "Planet Fitness", kind = "import", status = "completed")

val EXAMPLE_RUN_OUTCOME_NOOP_IMPORT =
    RunOutcomeSchema(run_id = 42, target = "Tesla Superchargers", kind = "import", status = "noop")

val EXAMPLE_RUN_OUTCOME_FAILED =
    RunOutcomeSchema(
        run_id = 42,
        target = "Rec.gov Campsites",
        kind = "import",
        status = "failed",
        failed_phase = "import:Rec.gov Campsites",
    )

val EXAMPLE_ERR_UNKNOWN_TARGET =
    ErrorUnknownTargetSchema(
        error = "unknown target",
        target = "nope",
        known =
            listOf(
                "Campflare Campgrounds",
                "Planet Fitness",
                "Rec.gov Campsites",
                "Tesla Superchargers",
            ),
    )

val EXAMPLE_ERR_TARGET_BUSY =
    ErrorTargetBusySchema(error = "target busy", target = "Rec.gov Campsites", running_run_id = 41)

val EXAMPLE_FAN_OUT_IMPORT =
    FanOutResponseSchema(
        kind = "import",
        outcomes =
            listOf(
                RunOutcomeSchema(run_id = 4, target = "Rec.gov Campgrounds", kind = "import", status = "completed"),
                RunOutcomeSchema(run_id = 5, target = "Planet Fitness", kind = "import", status = "completed"),
            ),
    )

val EXAMPLE_RUNS_LIST =
    RunsListSchema(
        runs =
            listOf(
                IngestRunListItemSchema(
                    id = 42,
                    target = "Rec.gov Campsites",
                    kind = "import",
                    status = "completed",
                    triggered_by = "admin-api",
                    started_at = "2026-06-06T19:14:02Z",
                    completed_at = "2026-06-06T19:18:31Z",
                ),
                IngestRunListItemSchema(
                    id = 41,
                    target = "Planet Fitness",
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
        target = "Rec.gov Campsites",
        kind = "import",
        status = "completed",
        triggered_by = "admin-api",
        started_at = "2026-06-06T19:14:02Z",
        completed_at = "2026-06-06T19:18:31Z",
        phases =
            listOf(
                IngestRunPhaseSchema(
                    id = 43,
                    phase = "import:Rec.gov Campsites",
                    phase_kind = "import",
                    status = "completed",
                    started_at = "2026-06-06T19:14:02Z",
                    completed_at = "2026-06-06T19:14:55Z",
                    exit_code = 0,
                ),
                IngestRunPhaseSchema(
                    id = 44,
                    phase = "import:Aspira Resources → Aspira Pins",
                    phase_kind = "import",
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
                    target = "Rec.gov Campsites",
                    last_run = 42,
                    kind = "import",
                    status = "completed",
                    age_sec = 3742,
                ),
                TargetStatusSchema(target = "Planet Fitness"),
            ),
    )

val EXAMPLE_ERR_NOT_FOUND_BAD_ID = ErrorNotFoundSchema(error = "bad id")

val EXAMPLE_ERR_NOT_FOUND = ErrorNotFoundSchema(error = "not found", id = 99)
