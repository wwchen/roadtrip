package ca.floo.roadtrip.ingest

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
// sequentially, in `defaultTargets` declaration order. The response is the
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
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("completed") {
                            value = """{"run_id":42,"target":"campgrounds","kind":"fetch","status":"completed"}"""
                        }
                        example("noop (no fetch phases)") {
                            value = """{"run_id":42,"target":"parks-canada-curated","kind":"fetch","status":"noop"}"""
                        }
                    }
                }
                code(HttpStatusCode.NotFound) {
                    description = "Target name is not in the static map"
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("unknown") {
                            value =
                                """{"error":"unknown target","target":"nope","known":["alberta-provincial","campgrounds","national-parks","parks-canada-curated","planet-fitness","state-parks","tesla-pricing"]}"""
                        }
                    }
                }
                code(HttpStatusCode.Conflict) {
                    description = "A run for this target is already in flight"
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("busy") {
                            value = """{"error":"target busy","target":"campgrounds","running_run_id":41}"""
                        }
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "A phase failed; failed_phase identifies which"
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("failed") {
                            value =
                                """{"run_id":42,"target":"campgrounds","kind":"fetch","status":"failed","failed_phase":"fetch_bc_parks.py"}"""
                        }
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
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("completed") {
                            value = """{"run_id":42,"target":"planet-fitness","kind":"import","status":"completed"}"""
                        }
                        example("noop (no import phases)") {
                            value = """{"run_id":42,"target":"tesla-pricing","kind":"import","status":"noop"}"""
                        }
                    }
                }
                code(HttpStatusCode.NotFound) {
                    body<String> { mediaTypes(ContentType.Application.Json) }
                }
                code(HttpStatusCode.Conflict) {
                    body<String> { mediaTypes(ContentType.Application.Json) }
                }
                code(HttpStatusCode.InternalServerError) {
                    body<String> { mediaTypes(ContentType.Application.Json) }
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
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("fan-out") {
                            value =
                                """{"kind":"fetch","outcomes":[{"run_id":1,"target":"alberta-provincial","kind":"fetch","status":"noop"},{"run_id":2,"target":"campgrounds","kind":"fetch","status":"completed"},{"run_id":3,"target":"planet-fitness","kind":"fetch","status":"completed"}]}"""
                        }
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    description = "At least one target failed; outcomes shows per-target status"
                    body<String> { mediaTypes(ContentType.Application.Json) }
                }
            }
        }) { runAll(controller, RunKind.FETCH) }

        post("/import", {
            tags = listOf("admin")
            summary = "Import data/ files for every known target (sequential fan-out)"
            response {
                code(HttpStatusCode.OK) {
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("fan-out") {
                            value =
                                """{"kind":"import","outcomes":[{"run_id":4,"target":"campgrounds","kind":"import","status":"completed"},{"run_id":5,"target":"planet-fitness","kind":"import","status":"completed"}]}"""
                        }
                    }
                }
                code(HttpStatusCode.InternalServerError) {
                    body<String> { mediaTypes(ContentType.Application.Json) }
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
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("two runs") {
                            value =
                                """{"runs":[{"id":42,"target":"campgrounds","kind":"fetch","status":"completed","triggered_by":"admin-api","started_at":"2026-06-06T19:14:02Z","completed_at":"2026-06-06T19:18:31Z"},{"id":41,"target":"planet-fitness","kind":"import","status":"completed","triggered_by":"admin-api","started_at":"2026-06-06T19:13:44Z","completed_at":"2026-06-06T19:13:46Z"}]}"""
                        }
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
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("with phases") {
                            value =
                                """{"id":42,"target":"campgrounds","kind":"fetch","status":"completed","triggered_by":"admin-api","started_at":"2026-06-06T19:14:02Z","completed_at":"2026-06-06T19:18:31Z","phases":[{"id":43,"phase":"fetch_campgrounds.py","phase_kind":"fetch","status":"completed","exit_code":0,"started_at":"2026-06-06T19:14:02Z","completed_at":"2026-06-06T19:14:55Z","counts":{"exit_code":0}},{"id":44,"phase":"fetch_bc_parks.py","phase_kind":"fetch","status":"completed","exit_code":0,"started_at":"2026-06-06T19:14:55Z","completed_at":"2026-06-06T19:15:30Z","counts":{"exit_code":0}}]}"""
                        }
                    }
                }
                code(HttpStatusCode.BadRequest) {
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("bad id") { value = """{"error":"bad id"}""" }
                    }
                }
                code(HttpStatusCode.NotFound) {
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("not found") { value = """{"error":"not found","id":99}""" }
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
                    body<String> {
                        mediaTypes(ContentType.Application.Json)
                        example("two targets") {
                            value =
                                """{"targets":[{"target":"campgrounds","last_run":42,"kind":"fetch","status":"completed","age_sec":3742},{"target":"planet-fitness","last_run":null,"kind":null,"status":null,"age_sec":null}]}"""
                        }
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
    val outcomes = mutableListOf<RunOutcome>()
    var anyFailed = false
    for (target in controller.knownTargets().sorted()) {
        try {
            val outcome = controller.startRun(target, kind, "admin-api")
            if (outcome.status == "failed") anyFailed = true
            outcomes.add(outcome)
        } catch (e: TargetBusyException) {
            // Skip a busy target rather than abort the whole fan-out.
            anyFailed = true
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
