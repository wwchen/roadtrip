package ca.floo.roadtrip.routes

import ca.floo.roadtrip.exceptions.TargetBusyException
import ca.floo.roadtrip.exceptions.TargetNotFoundException
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
import ca.floo.roadtrip.models.domain.ingest.IngestRunDetailRow
import ca.floo.roadtrip.models.domain.ingest.IngestRunListItemRow
import ca.floo.roadtrip.models.domain.ingest.IngestRunPhaseRow
import ca.floo.roadtrip.models.domain.ingest.TargetIngestStatusRow
import ca.floo.roadtrip.models.metadata.ingest.RunKind
import ca.floo.roadtrip.models.metadata.ingest.RunOutcome
import ca.floo.roadtrip.repo.AdminIngestReadRepo
import ca.floo.roadtrip.service.etl.framework.IngestController
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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
//   POST /api/admin/data/import[/{target}]   data/ → Postgres rows via Importer
//   GET  /api/admin/data/runs[?target=…|/:id] history
//   GET  /api/admin/data/status              per-target last-completed + age
//
// With no {target}, import fans out across every known target, sequentially, in
// registry order. Fetchers run outside the backend process via scripts/poll_raw.py.
//
// Auth boundary lives upstream at the Cloudflare Zero Trust path rule on
// /api/admin/* (existing tunnel). Locally the routes are reachable on
// 127.0.0.1:8765 directly for `make data-import`. If you ever expose dev to the
// internet, bind admin routes to loopback only.
fun Route.adminIngestRoutes(
    controller: IngestController,
    ctx: DSLContext,
) {
    val readRepo = AdminIngestReadRepo(ctx)

    route("/api/admin/data") {
        // One target — sync default; ?async=1 fires-and-forgets.
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
                    }
                }
                code(HttpStatusCode.NotFound) {
                    body<ErrorUnknownTargetSchema> {
                        mediaTypes(ContentType.Application.Json)
                    }
                }
                code(HttpStatusCode.Conflict) {
                    body<ErrorTargetBusySchema> {
                        mediaTypes(ContentType.Application.Json)
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    body<RunOutcomeSchema> {
                        mediaTypes(ContentType.Application.Json)
                    }
                }
            }
        }) { runOne(controller, RunKind.IMPORT) }

        // No target — fan out across every known target sequentially.
        post("/import", {
            tags = listOf("admin")
            summary = "Import data/ files for every known target (sequential fan-out)"
            response {
                code(HttpStatusCode.OK) {
                    body<FanOutResponseSchema> {
                        mediaTypes(ContentType.Application.Json)
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    body<FanOutResponseSchema> {
                        mediaTypes(ContentType.Application.Json)
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
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    body<ErrorNotFoundSchema> {
                        mediaTypes(ContentType.Application.Json)
                    }
                }
                code(HttpStatusCode.NotFound) {
                    body<ErrorNotFoundSchema> {
                        mediaTypes(ContentType.Application.Json)
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
        val outcome =
            withContext(NonCancellable) {
                controller.startRun(target, kind, "admin-api")
            }
        if (outcome.status == "completed") {
            withContext(NonCancellable) {
                controller.etl.refreshCanonicalViews()
            }
        }
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
    // Fan out sequentially. Import phases hit shared local files and Postgres;
    // keeping this serial preserves stable run history and predictable load.
    val log = org.slf4j.LoggerFactory.getLogger("AdminIngest.fanOut")
    val (outcomes, anyFailed) =
        withContext(NonCancellable) {
            val outcomes = mutableListOf<RunOutcome>()
            var anyFailed = false
            val all = controller.fanOutTargets(kind)
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
            if (outcomes.any { it.status == "completed" }) {
                log.info("fan-out: refreshing canonical views")
                controller.etl.refreshCanonicalViews()
            }
            val totalElapsed = (System.currentTimeMillis() - started) / 1000.0
            log.info(
                "fan-out done: kind={} targets={} elapsed={}s anyFailed={}",
                kind.rowValue,
                all.size,
                "%.1f".format(totalElapsed),
                anyFailed,
            )
            Pair(outcomes, anyFailed)
        }
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
