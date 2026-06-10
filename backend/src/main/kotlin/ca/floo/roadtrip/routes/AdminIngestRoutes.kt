package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.EXAMPLE_ERR_NOT_FOUND
import ca.floo.roadtrip.models.api.EXAMPLE_ERR_NOT_FOUND_BAD_ID
import ca.floo.roadtrip.models.api.EXAMPLE_ERR_TARGET_BUSY
import ca.floo.roadtrip.models.api.EXAMPLE_ERR_UNKNOWN_TARGET
import ca.floo.roadtrip.models.api.EXAMPLE_FAN_OUT_FETCH
import ca.floo.roadtrip.models.api.EXAMPLE_FAN_OUT_IMPORT
import ca.floo.roadtrip.models.api.EXAMPLE_RUNS_LIST
import ca.floo.roadtrip.models.api.EXAMPLE_RUN_DETAIL
import ca.floo.roadtrip.models.api.EXAMPLE_RUN_OUTCOME_COMPLETED_FETCH
import ca.floo.roadtrip.models.api.EXAMPLE_RUN_OUTCOME_COMPLETED_IMPORT
import ca.floo.roadtrip.models.api.EXAMPLE_RUN_OUTCOME_FAILED
import ca.floo.roadtrip.models.api.EXAMPLE_RUN_OUTCOME_NOOP
import ca.floo.roadtrip.models.api.EXAMPLE_RUN_OUTCOME_NOOP_IMPORT
import ca.floo.roadtrip.models.api.EXAMPLE_STATUS
import ca.floo.roadtrip.models.api.ErrorNotFoundSchema
import ca.floo.roadtrip.models.api.ErrorTargetBusySchema
import ca.floo.roadtrip.models.api.ErrorUnknownTargetSchema
import ca.floo.roadtrip.models.api.FanOutResponseSchema
import ca.floo.roadtrip.models.api.IngestRunListItemSchema
import ca.floo.roadtrip.models.api.IngestRunPhaseSchema
import ca.floo.roadtrip.models.api.RunDetailSchema
import ca.floo.roadtrip.models.api.RunOutcomeSchema
import ca.floo.roadtrip.models.api.RunsListSchema
import ca.floo.roadtrip.models.api.StatusResponseSchema
import ca.floo.roadtrip.models.api.TargetStatusSchema
import ca.floo.roadtrip.models.ingest.RunKind
import ca.floo.roadtrip.models.ingest.RunOutcome
import ca.floo.roadtrip.repo.AdminIngestReadRepo
import ca.floo.roadtrip.repo.IngestRunDetailRow
import ca.floo.roadtrip.repo.IngestRunListItemRow
import ca.floo.roadtrip.repo.IngestRunPhaseRow
import ca.floo.roadtrip.repo.TargetIngestStatusRow
import ca.floo.roadtrip.service.etl.IngestController
import ca.floo.roadtrip.service.etl.TargetBusyException
import ca.floo.roadtrip.service.etl.TargetNotFoundException
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext

@OptIn(ExperimentalSerializationApi::class)
private val adminIngestJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
    }

// Admin surface for the ingestion controller (RFC 0004 / issue #44).
//
// Vocabulary:
//   POST /api/admin/data/fetch[/{target}]    web → data/<target>.{json,geojson}
//   POST /api/admin/data/import[/{target}]   data/ → Postgres rows via Importer
//   GET  /api/admin/data/runs[?target=…|/:id] history
//   GET  /api/admin/data/status              per-target last-completed + age
//
// With no {target}, fetch and import fan out across every known target,
// sequentially, in `targetsFromRegistry` order (see config/poi-registry.yaml). The response is the
// per-target outcome list.
//
// Auth boundary lives upstream at the Cloudflare Zero Trust path rule on
// /api/admin/* (existing tunnel). Locally the routes are reachable on
// 127.0.0.1:8765 directly — Tilt buttons and `make data-fetch`/`data-import`
// curl them. If you ever expose dev to the internet, bind to loopback only.
fun Route.adminIngestRoutes(
    controller: IngestController,
    ctx: DSLContext,
) {
    val readRepo = AdminIngestReadRepo(ctx)

    route("/api/admin/data") {
        // One target — sync default; ?async=1 fires-and-forgets.
        post("/fetch/{target}", {
            tags = listOf("admin")
            summary = "Fetch upstream data into data/{target}.* for one target"
            request {
                pathParameter<String>("target") {
                    description = "Target name from /api/admin/data/status"
                    example("campgrounds") { value = "campgrounds" }
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Fetch completed (or no-op for fetch-less targets)"
                    body<RunOutcomeSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("completed") { value = EXAMPLE_RUN_OUTCOME_COMPLETED_FETCH }
                        example("noop (no fetch phases)") { value = EXAMPLE_RUN_OUTCOME_NOOP }
                    }
                }
                code(HttpStatusCode.NotFound) {
                    description = "Target name is not in the static map"
                    body<ErrorUnknownTargetSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("unknown") { value = EXAMPLE_ERR_UNKNOWN_TARGET }
                    }
                }
                code(HttpStatusCode.Conflict) {
                    description = "A run for this target is already in flight"
                    body<ErrorTargetBusySchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("busy") { value = EXAMPLE_ERR_TARGET_BUSY }
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "A phase failed; failed_phase identifies which"
                    body<RunOutcomeSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("failed") { value = EXAMPLE_RUN_OUTCOME_FAILED }
                    }
                }
            }
        }) { runOne(controller, RunKind.FETCH) }

        post("/import/{target}", {
            tags = listOf("admin")
            summary = "Import data/ files into Postgres for one target"
            request {
                pathParameter<String>("target") {
                    example("planet-fitness") { value = "planet-fitness" }
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    description = "Import completed (or no-op for import-less targets)"
                    body<RunOutcomeSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("completed") { value = EXAMPLE_RUN_OUTCOME_COMPLETED_IMPORT }
                        example("noop (no import phases)") { value = EXAMPLE_RUN_OUTCOME_NOOP_IMPORT }
                    }
                }
                code(HttpStatusCode.NotFound) {
                    body<ErrorUnknownTargetSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("unknown") { value = EXAMPLE_ERR_UNKNOWN_TARGET }
                    }
                }
                code(HttpStatusCode.Conflict) {
                    body<ErrorTargetBusySchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("busy") { value = EXAMPLE_ERR_TARGET_BUSY }
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    body<RunOutcomeSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("failed") { value = EXAMPLE_RUN_OUTCOME_FAILED }
                    }
                }
            }
        }) { runOne(controller, RunKind.IMPORT) }

        // No target — fan out across every known target sequentially.
        post("/fetch", {
            tags = listOf("admin")
            summary = "Fetch upstream data for every known target (sequential fan-out)"
            response {
                code(HttpStatusCode.OK) {
                    description = "All targets succeeded (or were no-ops)"
                    body<FanOutResponseSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("fan-out") { value = EXAMPLE_FAN_OUT_FETCH }
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "At least one target failed; outcomes shows per-target status"
                    body<FanOutResponseSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("fan-out") { value = EXAMPLE_FAN_OUT_FETCH }
                    }
                }
            }
        }) { runAll(controller, RunKind.FETCH) }

        post("/import", {
            tags = listOf("admin")
            summary = "Import data/ files for every known target (sequential fan-out)"
            response {
                code(HttpStatusCode.OK) {
                    body<FanOutResponseSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("fan-out") { value = EXAMPLE_FAN_OUT_IMPORT }
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    body<FanOutResponseSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("fan-out") { value = EXAMPLE_FAN_OUT_IMPORT }
                    }
                }
            }
        }) { runAll(controller, RunKind.IMPORT) }

        get("/runs", {
            tags = listOf("admin")
            summary = "Last 50 parent ingest runs (filter by ?target=)"
            request {
                queryParameter<String>("target") {
                    description = "Filter to one target. Omit for all targets."
                    required = false
                    example("campgrounds") { value = "campgrounds" }
                }
            }
            response {
                code(HttpStatusCode.OK) {
                    body<RunsListSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("two runs") { value = EXAMPLE_RUNS_LIST }
                    }
                }
            }
        }) {
            val target = call.request.queryParameters["target"]
            call.respondAdminJson(listRecent(readRepo, target, limit = 50))
        }

        get("/runs/{id}", {
            tags = listOf("admin")
            summary = "One ingest run with its ordered phase rows"
            request {
                pathParameter<Long>("id") { example("42") { value = 42L } }
            }
            response {
                code(HttpStatusCode.OK) {
                    body<RunDetailSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("with phases") { value = EXAMPLE_RUN_DETAIL }
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    body<ErrorNotFoundSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("bad id") { value = EXAMPLE_ERR_NOT_FOUND_BAD_ID }
                    }
                }
                code(HttpStatusCode.NotFound) {
                    body<ErrorNotFoundSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("not found") { value = EXAMPLE_ERR_NOT_FOUND }
                    }
                }
            }
        }) {
            val id = call.parameters["id"]?.toLongOrNull()
            if (id == null) {
                call.respondAdminJson(ErrorNotFoundSchema(error = "bad id"), HttpStatusCode.BadRequest)
                return@get
            }
            val body = runDetail(readRepo, id)
            if (body == null) {
                call.respondAdminJson(ErrorNotFoundSchema(error = "not found", id = id), HttpStatusCode.NotFound)
            } else {
                call.respondAdminJson(body)
            }
        }

        get("/status", {
            tags = listOf("admin")
            summary = "Per-target ingest run status + age in seconds"
            response {
                code(HttpStatusCode.OK) {
                    body<StatusResponseSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("two targets") { value = EXAMPLE_STATUS }
                    }
                }
            }
        }) {
            call.respondAdminJson(statusByTarget(readRepo, controller.knownTargets()))
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.runOne(
    controller: IngestController,
    kind: RunKind,
) {
    val target = call.parameters["target"]!!
    try {
        val outcome = controller.startRun(target, kind, "admin-api")
        val status =
            when (outcome.status) {
                "completed", "noop" -> HttpStatusCode.OK
                else -> HttpStatusCode.InternalServerError
            }
        call.respondAdminJson(outcome.toSchema(), status)
    } catch (_: TargetNotFoundException) {
        val known = controller.knownTargets().sorted()
        call.respondAdminJson(
            ErrorUnknownTargetSchema(error = "unknown target", target = target, known = known),
            HttpStatusCode.NotFound,
        )
    } catch (e: TargetBusyException) {
        call.respondAdminJson(
            ErrorTargetBusySchema(error = "target busy", target = e.target, running_run_id = e.runningRunId),
            HttpStatusCode.Conflict,
        )
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.runAll(
    controller: IngestController,
    kind: RunKind,
) {
    // Fan out sequentially. Concurrent might be tempting but parallel fetches
    // against the same upstream (rec.gov, OSM Overpass) burn rate-limit
    // budget for no real wall-clock savings on a manual refresh.
    val log = org.slf4j.LoggerFactory.getLogger("AdminIngest.fanOut")
    val outcomes = mutableListOf<RunOutcome>()
    var anyFailed = false
    val all = controller.fanOutTargets(kind).sorted()
    val started = System.currentTimeMillis()
    log.info("fan-out start: kind={} targets={}", kind.rowValue, all.size)
    for ((idx, target) in all.withIndex()) {
        val targetStarted = System.currentTimeMillis()
        log.info("fan-out [{}/{}] target={} starting", idx + 1, all.size, target)
        try {
            val outcome = controller.startRun(target, kind, "admin-api")
            if (outcome.status == "failed") anyFailed = true
            outcomes.add(outcome)
            val elapsed = (System.currentTimeMillis() - targetStarted) / 1000.0
            log.info(
                "fan-out [{}/{}] target={} {} ({}s)",
                idx + 1,
                all.size,
                target,
                outcome.status,
                "%.1f".format(elapsed),
            )
        } catch (e: TargetBusyException) {
            // Skip a busy target rather than abort the whole fan-out.
            anyFailed = true
            log.warn("fan-out [{}/{}] target={} busy", idx + 1, all.size, target)
            outcomes.add(
                RunOutcome(
                    parentRunId = e.runningRunId,
                    target = e.target,
                    kind = kind,
                    status = "busy",
                    failedPhase = null,
                ),
            )
        }
    }
    val totalElapsed = (System.currentTimeMillis() - started) / 1000.0
    log.info(
        "fan-out done: kind={} targets={} elapsed={}s anyFailed={}",
        kind.rowValue,
        all.size,
        "%.1f".format(totalElapsed),
        anyFailed,
    )
    val status = if (anyFailed) HttpStatusCode.InternalServerError else HttpStatusCode.OK
    call.respondAdminJson(
        FanOutResponseSchema(
            kind = kind.rowValue,
            outcomes = outcomes.map { it.toSchema() },
        ),
        status,
    )
}

private fun listRecent(
    readRepo: AdminIngestReadRepo,
    target: String?,
    limit: Int,
): RunsListSchema =
    RunsListSchema(
        runs = readRepo.listRecent(target, limit).map { it.toSchema() },
    )

private fun runDetail(
    readRepo: AdminIngestReadRepo,
    id: Long,
): RunDetailSchema? = readRepo.runDetail(id)?.toSchema()

private fun statusByTarget(
    readRepo: AdminIngestReadRepo,
    targets: Set<String>,
): StatusResponseSchema =
    StatusResponseSchema(
        targets = readRepo.statusByTarget(targets).map { it.toSchema() },
    )

private suspend inline fun <reified T> ApplicationCall.respondAdminJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(adminIngestJson.encodeToString(value), ContentType.Application.Json, status)
}

private fun RunOutcome.toSchema(): RunOutcomeSchema =
    RunOutcomeSchema(
        run_id = parentRunId,
        target = target,
        kind = kind.rowValue,
        status = status,
        failed_phase = failedPhase,
    )

private fun IngestRunListItemRow.toSchema(): IngestRunListItemSchema =
    IngestRunListItemSchema(
        id = id,
        target = target,
        kind = kind,
        status = status,
        triggered_by = triggeredBy,
        started_at = startedAt.toString(),
        completed_at = completedAt?.toString(),
    )

private fun IngestRunDetailRow.toSchema(): RunDetailSchema =
    RunDetailSchema(
        id = id,
        target = target,
        kind = kind,
        status = status,
        triggered_by = triggeredBy,
        started_at = startedAt.toString(),
        completed_at = completedAt?.toString(),
        notes = notes,
        phases = phases.map { it.toSchema() },
    )

private fun IngestRunPhaseRow.toSchema(): IngestRunPhaseSchema =
    IngestRunPhaseSchema(
        id = id,
        phase = phase,
        phase_kind = phaseKind,
        status = status,
        exit_code = exitCode,
        started_at = startedAt.toString(),
        completed_at = completedAt?.toString(),
        counts = countsJson?.let { adminIngestJson.parseToJsonElement(it) },
        notes = notes,
    )

private fun TargetIngestStatusRow.toSchema(): TargetStatusSchema =
    TargetStatusSchema(
        target = target,
        last_run = lastRun,
        kind = kind,
        status = status,
        age_sec = ageSec,
    )
