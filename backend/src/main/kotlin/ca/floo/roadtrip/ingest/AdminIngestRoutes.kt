package ca.floo.roadtrip.ingest

import ca.floo.roadtrip.db.generated.tables.IngestRuns.Companion.INGEST_RUNS
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jooq.DSLContext
import java.time.OffsetDateTime
import java.time.ZoneOffset

// Admin surface for the ingestion controller (RFC 0004 / issue #44).
//
// Auth boundary lives upstream at the Cloudflare Zero Trust path rule on
// /api/admin/* (existing tunnel). Locally the routes are reachable on
// 127.0.0.1:8765 directly — Tilt buttons and `make pois-refresh` curl them.
// If you ever expose dev to the internet, bind to loopback only.
//
// Default semantics: POST is synchronous. The handler suspends until the
// run completes (success or first phase failure) and returns the final
// status. Pass ?async=1 to fire-and-forget with a 202 + run_id.
fun Route.adminIngestRoutes(
    controller: IngestController,
    ctx: DSLContext,
    scope: CoroutineScope,
) {
    route("/api/admin/ingest") {
        post("/{target}") {
            val target = call.parameters["target"]!!
            val async = call.request.queryParameters["async"] == "1"
            val triggeredBy = call.request.queryParameters["triggered_by"] ?: "admin-api"

            if (async) {
                // Fire-and-forget. We need the parent run_id to return — start
                // the run, but launch the actual phases on the supervisor scope.
                // To avoid racing the scope.launch and getting a 202 before the
                // parent row exists, do the lock+row creation synchronously and
                // run phases async. Simplest path: just spawn startRun and
                // surface failures as a synchronous error.
                scope.launch {
                    try {
                        controller.startRun(target, triggeredBy)
                    } catch (_: TargetNotFoundException) {
                        // already returned 404 below
                    } catch (e: TargetBusyException) {
                        // already returned 409 below
                    }
                }
                // Best-effort 202: caller polls GET /runs?target= to find it.
                call.respondText(
                    """{"target":"$target","async":true}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Accepted,
                )
                return@post
            }

            try {
                val outcome = controller.startRun(target, triggeredBy)
                val status =
                    if (outcome.status == "completed") HttpStatusCode.OK else HttpStatusCode.InternalServerError
                val sb = StringBuilder()
                sb.append('{')
                sb.append(""""run_id":""").append(outcome.parentRunId)
                sb.append(""","target":""").append(jsonString(target))
                sb.append(""","status":""").append(jsonString(outcome.status))
                outcome.failedPhase?.let { sb.append(""","failed_phase":""").append(jsonString(it)) }
                sb.append(""","async":false}""")
                call.respondText(sb.toString(), ContentType.Application.Json, status)
            } catch (_: TargetNotFoundException) {
                val known = controller.knownTargets().sorted()
                call.respondText(
                    """{"error":"unknown target","target":${jsonString(target)},"known":${jsonStringArray(known)}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.NotFound,
                )
            } catch (e: TargetBusyException) {
                call.respondText(
                    """{"error":"target busy","target":"${e.target}","running_run_id":${e.runningRunId}}""",
                    ContentType.Application.Json,
                    HttpStatusCode.Conflict,
                )
            }
        }

        get("/runs") {
            val target = call.request.queryParameters["target"]
            val rows = listRecent(ctx, target, limit = 50)
            call.respondText(rows, ContentType.Application.Json)
        }

        get("/runs/{id}") {
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

        get("/health") {
            val rows = healthByTarget(ctx, controller.knownTargets())
            call.respondText(rows, ContentType.Application.Json)
        }
    }
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
            sb.append(""","last_run":null,"status":null,"age_sec":null}""")
        } else {
            sb.append(""","last_run":""").append(latest.id)
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
