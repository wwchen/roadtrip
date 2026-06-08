package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.api.EXAMPLE_ERR_NOT_FOUND
import ca.floo.roadtrip.api.EXAMPLE_ERR_NOT_FOUND_BAD_ID
import ca.floo.roadtrip.api.EXAMPLE_ERR_TARGET_BUSY
import ca.floo.roadtrip.api.EXAMPLE_ERR_UNKNOWN_TARGET
import ca.floo.roadtrip.api.EXAMPLE_FAN_OUT_FETCH
import ca.floo.roadtrip.api.EXAMPLE_FAN_OUT_IMPORT
import ca.floo.roadtrip.api.EXAMPLE_HEALTH
import ca.floo.roadtrip.api.EXAMPLE_RUNS_LIST
import ca.floo.roadtrip.api.EXAMPLE_RUN_DETAIL
import ca.floo.roadtrip.api.EXAMPLE_RUN_OUTCOME_COMPLETED_FETCH
import ca.floo.roadtrip.api.EXAMPLE_RUN_OUTCOME_COMPLETED_IMPORT
import ca.floo.roadtrip.api.EXAMPLE_RUN_OUTCOME_FAILED
import ca.floo.roadtrip.api.EXAMPLE_RUN_OUTCOME_NOOP
import ca.floo.roadtrip.api.EXAMPLE_RUN_OUTCOME_NOOP_IMPORT
import ca.floo.roadtrip.api.ErrorNotFoundSchema
import ca.floo.roadtrip.api.ErrorTargetBusySchema
import ca.floo.roadtrip.api.ErrorUnknownTargetSchema
import ca.floo.roadtrip.api.FanOutResponseSchema
import ca.floo.roadtrip.api.HealthResponseSchema
import ca.floo.roadtrip.api.RunDetailSchema
import ca.floo.roadtrip.api.RunOutcomeSchema
import ca.floo.roadtrip.api.RunsListSchema
import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.route
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Admin surface for the ingestion controller (RFC 0004 / issue #44).
//
// Vocabulary:
//   POST /api/admin/data/fetch[/{target}]    web → data/<target>.{json,geojson}
//   POST /api/admin/data/import[/{target}]   data/ → Postgres rows via Importer
//   GET  /api/admin/data/runs[?target=…|/:id] history
//   GET  /api/admin/data/health              per-target last-completed + age
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
    route("/api/admin/data") {
        // One target — sync default; ?async=1 fires-and-forgets.
        post("/fetch/{target}", {
            tags = listOf("admin")
            summary = "Fetch upstream data into data/{target}.* for one target"
            request {
                pathParameter<String>("target") {
                    description = "Target name from /api/admin/data/health"
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
            call.respondText(listRecent(ctx, target, limit = 50), ContentType.Application.Json)
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
                call.respondText("""{"error":"bad id"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
                return@get
            }
            val body = runDetail(ctx, id)
            if (body == null) {
                call.respondText(
                    """{"error":"not found","id":$id}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            } else {
                call.respondText(body, ContentType.Application.Json)
            }
        }

        get("/health", {
            tags = listOf("admin")
            summary = "Per-target last-completed run + age in seconds"
            response {
                code(HttpStatusCode.OK) {
                    body<HealthResponseSchema> {
                        mediaTypes(ContentType.Application.Json)
                        example("two targets") { value = EXAMPLE_HEALTH }
                    }
                }
            }
        }) {
            call.respondText(healthByTarget(ctx, controller.knownTargets()), ContentType.Application.Json)
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
        call.respondText(outcomeJson(outcome), ContentType.Application.Json, status)
    } catch (_: TargetNotFoundException) {
        val known = controller.knownTargets().sorted()
        call.respondText(
            """{"error":"unknown target","target":${jsonString(target)},"known":${jsonStringArray(known)}}""",
            ContentType.Application.Json,
            HttpStatusCode.NotFound,
        )
    } catch (e: TargetBusyException) {
        call.respondText(
            """{"error":"target busy","target":${jsonString(e.target)},"running_run_id":${e.runningRunId}}""",
            ContentType.Application.Json,
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
    val sb = StringBuilder("""{"kind":""").append(jsonString(kind.rowValue)).append(""","outcomes":[""")
    outcomes.forEachIndexed { i, o ->
        if (i > 0) sb.append(',')
        sb.append(outcomeJson(o))
    }
    sb.append("]}")
    call.respondText(sb.toString(), ContentType.Application.Json, status)
}

private fun outcomeJson(o: RunOutcome): String {
    val sb = StringBuilder()
    sb.append('{')
    sb.append(""""run_id":""").append(o.parentRunId)
    sb.append(""","target":""").append(jsonString(o.target))
    sb.append(""","kind":""").append(jsonString(o.kind.rowValue))
    sb.append(""","status":""").append(jsonString(o.status))
    o.failedPhase?.let { sb.append(""","failed_phase":""").append(jsonString(it)) }
    sb.append('}')
    return sb.toString()
}

private fun listRecent(
    ctx: DSLContext,
    target: String?,
    limit: Int,
): String {
    val q =
        ctx
            .select(
                INGEST_RUNS.ID,
                INGEST_RUNS.TARGET,
                INGEST_RUNS.PHASE,
                INGEST_RUNS.PHASE_KIND,
                INGEST_RUNS.PARENT_RUN_ID,
                INGEST_RUNS.STATUS,
                INGEST_RUNS.STARTED_AT,
                INGEST_RUNS.COMPLETED_AT,
                INGEST_RUNS.EXIT_CODE,
                INGEST_RUNS.TRIGGERED_BY,
            ).from(INGEST_RUNS)
            // Parent rows only — phase detail comes via /runs/{id}.
            .where(INGEST_RUNS.PHASE_KIND.eq("target"))
            .apply { if (target != null) and(INGEST_RUNS.TARGET.eq(target)) }
            .orderBy(INGEST_RUNS.STARTED_AT.desc())
            .limit(limit)
            .fetch()

    val sb = StringBuilder("""{"runs":[""")
    q.forEachIndexed { i, r ->
        if (i > 0) sb.append(',')
        sb.append("""{"id":""").append(r.get(INGEST_RUNS.ID))
        sb.append(""","target":""").append(jsonString(r.get(INGEST_RUNS.TARGET)!!))
        // Parent row's `phase` column carries the run kind (fetch | import).
        sb.append(""","kind":""").append(jsonString(r.get(INGEST_RUNS.PHASE)!!))
        sb.append(""","status":""").append(jsonString(r.get(INGEST_RUNS.STATUS)!!))
        sb.append(""","triggered_by":""").append(jsonString(r.get(INGEST_RUNS.TRIGGERED_BY)!!))
        sb.append(""","started_at":""").append(jsonString(r.get(INGEST_RUNS.STARTED_AT)!!.toString()))
        val done = r.get(INGEST_RUNS.COMPLETED_AT)
        if (done != null) sb.append(""","completed_at":""").append(jsonString(done.toString()))
        sb.append('}')
    }
    sb.append("]}")
    return sb.toString()
}

private fun runDetail(
    ctx: DSLContext,
    id: Long,
): String? {
    val parent =
        ctx
            .selectFrom(INGEST_RUNS)
            .where(INGEST_RUNS.ID.eq(id))
            .and(INGEST_RUNS.PHASE_KIND.eq("target"))
            .fetchOne() ?: return null

    val phases =
        ctx
            .selectFrom(INGEST_RUNS)
            .where(INGEST_RUNS.PARENT_RUN_ID.eq(id))
            .orderBy(INGEST_RUNS.ID.asc())
            .fetch()

    val sb = StringBuilder()
    sb.append('{')
    sb.append(""""id":""").append(parent.id)
    sb.append(""","target":""").append(jsonString(parent.target!!))
    sb.append(""","kind":""").append(jsonString(parent.phase!!))
    sb.append(""","status":""").append(jsonString(parent.status!!))
    sb.append(""","triggered_by":""").append(jsonString(parent.triggeredBy!!))
    sb.append(""","started_at":""").append(jsonString(parent.startedAt!!.toString()))
    parent.completedAt?.let { sb.append(""","completed_at":""").append(jsonString(it.toString())) }
    parent.notes?.let { sb.append(""","notes":""").append(jsonString(it)) }
    sb.append(""","phases":[""")
    phases.forEachIndexed { i, p ->
        if (i > 0) sb.append(',')
        sb.append('{')
        sb.append(""""id":""").append(p.id)
        sb.append(""","phase":""").append(jsonString(p.phase!!))
        sb.append(""","phase_kind":""").append(jsonString(p.phaseKind!!))
        sb.append(""","status":""").append(jsonString(p.status!!))
        p.exitCode?.let { sb.append(""","exit_code":""").append(it) }
        sb.append(""","started_at":""").append(jsonString(p.startedAt!!.toString()))
        p.completedAt?.let { sb.append(""","completed_at":""").append(jsonString(it.toString())) }
        p.counts?.let { sb.append(""","counts":""").append(it.data()) }
        p.notes?.let { sb.append(""","notes":""").append(jsonString(it)) }
        sb.append('}')
    }
    sb.append("]}")
    return sb.toString()
}

private fun healthByTarget(
    ctx: DSLContext,
    targets: Set<String>,
): String {
    val now = OffsetDateTime.now(ZoneOffset.UTC)
    val sb = StringBuilder("""{"targets":[""")
    var first = true
    for (t in targets.sorted()) {
        if (!first) sb.append(',')
        first = false
        val latest =
            ctx
                .selectFrom(INGEST_RUNS)
                .where(INGEST_RUNS.TARGET.eq(t))
                .and(INGEST_RUNS.PHASE_KIND.eq("target"))
                .and(INGEST_RUNS.STATUS.`in`("completed", "failed"))
                .orderBy(INGEST_RUNS.STARTED_AT.desc())
                .limit(1)
                .fetchOne()
        sb.append('{')
        sb.append(""""target":""").append(jsonString(t))
        if (latest == null) {
            sb.append(""","last_run":null,"kind":null,"status":null,"age_sec":null}""")
        } else {
            sb.append(""","last_run":""").append(latest.id)
            sb.append(""","kind":""").append(jsonString(latest.phase!!))
            sb.append(""","status":""").append(jsonString(latest.status!!))
            val ageSec =
                java.time.Duration
                    .between(latest.completedAt ?: latest.startedAt, now)
                    .seconds
            sb.append(""","age_sec":""").append(ageSec).append('}')
        }
    }
    sb.append("]}")
    return sb.toString()
}

private fun jsonString(s: String): String {
    val sb = StringBuilder(s.length + 2)
    sb.append('"')
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
    }
    sb.append('"')
    return sb.toString()
}

private fun jsonStringArray(items: List<String>): String = items.joinToString(",", prefix = "[", postfix = "]") { jsonString(it) }
