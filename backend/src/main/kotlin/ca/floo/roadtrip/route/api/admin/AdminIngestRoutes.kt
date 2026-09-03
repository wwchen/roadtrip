package ca.floo.roadtrip.route.api.admin

import ca.floo.roadtrip.model.api.ErrorNotFoundSchema
import ca.floo.roadtrip.model.api.ErrorTargetBusySchema
import ca.floo.roadtrip.model.api.ErrorUnknownTargetSchema
import ca.floo.roadtrip.model.api.FanOutResponseSchema
import ca.floo.roadtrip.model.api.IngestRunListItemSchema
import ca.floo.roadtrip.model.api.IngestRunPhaseSchema
import ca.floo.roadtrip.model.api.RunDetailSchema
import ca.floo.roadtrip.model.api.RunOutcomeSchema
import ca.floo.roadtrip.model.api.RunsListSchema
import ca.floo.roadtrip.model.api.StatusResponseSchema
import ca.floo.roadtrip.model.api.TargetStatusSchema
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.model.domain.ingest.IngestRunDetailRow
import ca.floo.roadtrip.model.domain.ingest.IngestRunListItemRow
import ca.floo.roadtrip.model.domain.ingest.IngestRunPhaseRow
import ca.floo.roadtrip.model.domain.ingest.TargetIngestStatusRow
import ca.floo.roadtrip.model.metadata.ingest.RunKind
import ca.floo.roadtrip.model.metadata.ingest.RunOutcome
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.longPath
import ca.floo.roadtrip.route.common.pathParam
import ca.floo.roadtrip.route.common.queryParam
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.roadtripApiJson
import ca.floo.roadtrip.service.etl.framework.IngestController
import ca.floo.roadtrip.support.TargetBusyException
import ca.floo.roadtrip.support.TargetNotFoundException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// How many parent runs the admin runs list returns; the page shows one screenful.
private const val RECENT_RUNS_LIMIT = 50

// Admin surface for the ingestion controller (RFC 0004 / issue #44).
//
// Vocabulary:
//   POST /api/admin/data/import[/{target}]   data/ → Postgres rows via Importer
//   GET  /api/admin/data/runs[?target=…|/:id] history
//   GET  /api/admin/data/status              per-target last-completed + age
//
// With no {target}, import fans out across every known target, sequentially, in
// `targetsFromRegistry` order (see the POI registry resource). The response is
// the per-target outcome list. Fetchers run outside the backend process.
//
// Auth boundary lives upstream at the Cloudflare Zero Trust path rule on
// /api/admin/* (existing tunnel). Locally the routes are reachable on
// 127.0.0.1:8765 directly for Tilt buttons and `make data-import`. If you ever
// expose dev to the internet, bind to loopback only.
fun Route.adminIngestRoutes(controller: IngestController) {
    route("/api") {
        route("/admin") {
            route("/data") {
                route("/import") {
                    post("/{target}") { runOne(controller, RunKind.IMPORT) }
                        .describeApi("admin", "Import data/ files into Postgres for one target")
                        .access(RouteAccess.Anonymous)

                    post { runAll(controller, RunKind.IMPORT) }
                        .describeApi("admin", "Import data/ files for every known target (sequential fan-out)")
                        .access(RouteAccess.Anonymous)
                }

                route("/runs") {
                    get {
                        val target = call.queryParam("target")
                        call.respondEncodedJson(listRecent(controller, target, limit = RECENT_RUNS_LIMIT))
                    }.describeApi("admin", "Last $RECENT_RUNS_LIMIT parent ingest runs (filter by ?target=)")
                        .access(RouteAccess.Anonymous)

                    get("/{id}") {
                        val id = call.longPath("id")
                        if (id == null) {
                            call.respondEncodedJson(ErrorNotFoundSchema(error = "bad id"), HttpStatusCode.BadRequest)
                            return@get
                        }
                        val body = runDetail(controller, id)
                        if (body == null) {
                            call.respondEncodedJson(ErrorNotFoundSchema(error = "not found", id = id), HttpStatusCode.NotFound)
                        } else {
                            call.respondEncodedJson(body)
                        }
                    }.describeApi("admin", "One ingest run with its ordered phase rows")
                        .access(RouteAccess.Anonymous)
                }

                get("/status") {
                    call.respondEncodedJson(statusByTarget(controller))
                }.describeApi("admin", "Per-target ingest run status + age in seconds")
                    .access(RouteAccess.Anonymous)
            }
        }
    }
}

private suspend fun io.ktor.server.routing.RoutingContext.runOne(
    controller: IngestController,
    kind: RunKind,
) {
    val target = call.pathParam("target")!!
    try {
        val outcome =
            withContext(NonCancellable) {
                controller.startRun(target, kind, "admin-api")
            }
        val status =
            when (outcome.status) {
                "completed", "noop" -> HttpStatusCode.OK
                else -> HttpStatusCode.InternalServerError
            }
        call.respondEncodedJson(outcome.toSchema(), status)
    } catch (_: TargetNotFoundException) {
        val known = controller.knownTargets().sorted()
        call.respondEncodedJson(
            ErrorUnknownTargetSchema(error = "unknown target", target = target, known = known),
            HttpStatusCode.NotFound,
        )
    } catch (e: TargetBusyException) {
        call.respondEncodedJson(
            ErrorTargetBusySchema(error = "target busy", target = e.target, runningRunId = e.runningRunId),
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
    call.respondEncodedJson(
        FanOutResponseSchema(
            kind = kind.rowValue,
            outcomes = outcomes.map { it.toSchema() },
        ),
        status,
    )
}

private fun listRecent(
    controller: IngestController,
    target: String?,
    limit: Int,
): RunsListSchema =
    RunsListSchema(
        runs = controller.recentRuns(target, limit).map { it.toSchema() },
    )

private fun runDetail(
    controller: IngestController,
    id: Long,
): RunDetailSchema? = controller.runDetail(id)?.toSchema()

private fun statusByTarget(controller: IngestController): StatusResponseSchema =
    StatusResponseSchema(
        targets = controller.statusByKnownTarget().map { it.toSchema() },
    )

private fun RunOutcome.toSchema(): RunOutcomeSchema =
    RunOutcomeSchema(
        runId = parentRunId,
        target = target,
        kind = kind.rowValue,
        status = status,
        failedPhase = failedPhase,
    )

private fun IngestRunListItemRow.toSchema(): IngestRunListItemSchema =
    IngestRunListItemSchema(
        id = id,
        target = target,
        kind = kind,
        status = status,
        triggeredBy = triggeredBy,
        startedAt = startedAt.toString(),
        completedAt = completedAt?.toString(),
    )

private fun IngestRunDetailRow.toSchema(): RunDetailSchema =
    RunDetailSchema(
        id = id,
        target = target,
        kind = kind,
        status = status,
        triggeredBy = triggeredBy,
        startedAt = startedAt.toString(),
        completedAt = completedAt?.toString(),
        notes = notes,
        phases = phases.map { it.toSchema() },
    )

private fun IngestRunPhaseRow.toSchema(): IngestRunPhaseSchema =
    IngestRunPhaseSchema(
        id = id,
        phase = phase,
        phaseKind = phaseKind,
        status = status,
        exitCode = exitCode,
        startedAt = startedAt.toString(),
        completedAt = completedAt?.toString(),
        counts = countsJson?.let { roadtripApiJson.parseToJsonElement(it) },
        notes = notes,
    )

private fun TargetIngestStatusRow.toSchema(): TargetStatusSchema =
    TargetStatusSchema(
        target = target,
        lastRun = lastRun,
        kind = kind,
        status = status,
        ageSec = ageSec,
    )
