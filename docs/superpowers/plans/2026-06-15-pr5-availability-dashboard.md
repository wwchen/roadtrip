# Availability Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Operator dashboard at `/availability` with three tabs — Jobs, Runs, Snapshots — that surface what the polling stack is doing. Read-only across all three. Provenance links connect watch → job → run → snapshots so investigating "why did this fire" takes four clicks.

**Architecture:** Backend exposes three new GET endpoints (`/api/availability/jobs`, `/jobs/{id}/runs`, `/snapshots`) plus a tiny aggregate (`GET /api/availability/jobs/summary` returning counts by status). Frontend is one HTML page with three tabs that share the existing `catalog.css` styling. Each tab is its own JS module under `web/components/availability/`. Jobs tab uses URL params for filtering + drill-down; clicking a job's id navigates to the Runs tab pre-filtered to that job; clicking a run shows its produced snapshots. No new tables, no new repo work — everything composes existing repos (`AvailabilityJobRepo`, `AvailabilityJobRunRepo`, `AvailabilitySnapshotRepo`).

**Tech Stack:** Kotlin/Ktor, jOOQ, Postgres. Vanilla JS frontend with shared `web/api/http.js` helpers and `web/components/catalog.css`.

**Reference docs:** `docs/superpowers/specs/2026-06-15-availability-watches-design.md` (entity model — see "Pages" section for the dashboard mockup). Prior PRs: #226 (watches), #227 (jobs+scheduler), #228 (job runs), #229 (snapshot rename + run_id FK).

**Stack base:** Branch from `avail-snapshots` (PR #229).

---

## File map

**Created:**

- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt` — DTOs for the three GETs (`AvailabilityJobSchema`, `AvailabilityJobsListResponse`, `AvailabilityJobRunSchema`, `AvailabilityJobRunsListResponse`, `AvailabilitySnapshotSchema`, `AvailabilitySnapshotsListResponse`, `AvailabilityJobsSummary`).
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutes.kt` — the three GET endpoints.
- `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutesTest.kt` — Testcontainers integration tests.
- `availability.html` (repo root) — admin page shell with three tabs.
- `web/availability.js` — page bootstrap + tab switching.
- `web/api/availability-dashboard-api.js` — client for the three GETs.
- `web/components/availability/jobs-tab.js` — Jobs tab module.
- `web/components/availability/runs-tab.js` — Runs tab module.
- `web/components/availability/snapshots-tab.js` — Snapshots tab module.

**Modified:**

- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt` — add `list(...)` + `count(...)` for the dashboard's filterable list, and `summary()` for the per-status counters.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt` — add `listSince(...)` for the runs tab's "last 100 runs across all jobs" query.
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt` — add read methods (`listForReservable`, `listForRun`).
- `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt` — wire `availabilityDashboardRoutes(...)`, route `/availability` (and `/availability/`) to `availability.html`.
- `pois.html`, `reservables.html`, `watches.html` — add `<a href="/availability">Activity</a>` to the catalog nav so operators can navigate between the four pages.
- `docker-compose.yml` — bind-mount `availability.html` into the container.

**Untouched:**

- The polling logic itself (executor, scheduler) — dashboard is pure read-side.
- `availability_job_run.snapshot_count` — keep using the denormalized counter for cheap row rendering. The runs tab does not call `count(*)` on snapshots.

---

## Task 1: Add `list`, `count`, `summary` to `AvailabilityJobRepo`

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt`

The existing repo (created in PR 2) has `upsertForWatch`, `findById`, `findByWatchId`, `deleteForWatch`, `claimDue`, `release`, `reclaimExpired`. None of those return a filterable list for the dashboard.

- [ ] **Step 1: Add three new methods**

Open `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt`. Inside the class, just below `findByWatchId`, add:

```kotlin
    /**
     * Filtered list of jobs newest-first by created_at. Used by the
     * /availability dashboard's Jobs tab.
     */
    fun list(
        status: String? = null,
        watchId: Long? = null,
        limit: Int = 100,
        offset: Int = 0,
    ): List<Job> {
        val effectiveLimit = limit.coerceIn(1, 500)
        val conds = mutableListOf<org.jooq.Condition>()
        if (status != null) conds += AVAILABILITY_JOB.STATUS.eq(status)
        if (watchId != null) conds += AVAILABILITY_JOB.WATCH_ID.eq(watchId)
        return ctx
            .selectFrom(AVAILABILITY_JOB)
            .where(if (conds.isEmpty()) DSL.noCondition() else DSL.and(conds))
            .orderBy(AVAILABILITY_JOB.CREATED_AT.desc(), AVAILABILITY_JOB.ID.desc())
            .limit(effectiveLimit)
            .offset(offset.coerceAtLeast(0))
            .fetch { fromRecord(it) }
    }

    fun count(
        status: String? = null,
        watchId: Long? = null,
    ): Int {
        val conds = mutableListOf<org.jooq.Condition>()
        if (status != null) conds += AVAILABILITY_JOB.STATUS.eq(status)
        if (watchId != null) conds += AVAILABILITY_JOB.WATCH_ID.eq(watchId)
        return ctx
            .selectCount()
            .from(AVAILABILITY_JOB)
            .where(if (conds.isEmpty()) DSL.noCondition() else DSL.and(conds))
            .fetchOne(0, Int::class.java) ?: 0
    }

    /**
     * Per-status counts plus a "due now" tally. One DB round-trip via
     * conditional aggregates so the dashboard counter row is cheap.
     */
    data class Summary(
        val active: Int,
        val paused: Int,
        val done: Int,
        val dueNow: Int,
        val claimed: Int,
    )

    fun summary(now: OffsetDateTime): Summary {
        val record =
            ctx
                .select(
                    DSL.count(DSL.case_().`when`(AVAILABILITY_JOB.STATUS.eq("active"), 1)).`as`("active"),
                    DSL.count(DSL.case_().`when`(AVAILABILITY_JOB.STATUS.eq("paused"), 1)).`as`("paused"),
                    DSL.count(DSL.case_().`when`(AVAILABILITY_JOB.STATUS.eq("done"), 1)).`as`("done"),
                    DSL
                        .count(
                            DSL
                                .case_()
                                .`when`(
                                    AVAILABILITY_JOB.STATUS
                                        .eq("active")
                                        .and(AVAILABILITY_JOB.NEXT_RUN_AT.le(now))
                                        .and(
                                            AVAILABILITY_JOB.CLAIMED_UNTIL.isNull
                                                .or(AVAILABILITY_JOB.CLAIMED_UNTIL.lt(now)),
                                        ),
                                    1,
                                ),
                        ).`as`("due_now"),
                    DSL
                        .count(
                            DSL
                                .case_()
                                .`when`(
                                    AVAILABILITY_JOB.CLAIMED_UNTIL.isNotNull
                                        .and(AVAILABILITY_JOB.CLAIMED_UNTIL.ge(now)),
                                    1,
                                ),
                        ).`as`("claimed"),
                ).from(AVAILABILITY_JOB)
                .fetchOne()!!
        return Summary(
            active = record.get("active", Int::class.java),
            paused = record.get("paused", Int::class.java),
            done = record.get("done", Int::class.java),
            dueNow = record.get("due_now", Int::class.java),
            claimed = record.get("claimed", Int::class.java),
        )
    }
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRepo.kt
git commit -m "AvailabilityJobRepo: add list, count, summary for dashboard"
```

---

## Task 2: Add `listSince` to `AvailabilityJobRunRepo`

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt`

The existing repo has `start`, `complete`, `fail`, `findById`, `listForJob`. The Runs tab needs a "last N runs across all jobs" view, optionally filtered by `since`, `status`, `jobId`.

- [ ] **Step 1: Add the method**

Open `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt`. Just below `listForJob`, add:

```kotlin
    /**
     * Recent runs across all jobs newest-first. Optional filters:
     * - [since]: only runs whose started_at is after this instant
     * - [status]: 'started' | 'completed' | 'failed'
     * - [jobId]: scope to one job (used by drill-down from Jobs tab)
     */
    fun listSince(
        since: OffsetDateTime? = null,
        status: String? = null,
        jobId: Long? = null,
        limit: Int = 100,
    ): List<Run> {
        val conds = mutableListOf<org.jooq.Condition>()
        if (since != null) conds += AVAILABILITY_JOB_RUN.STARTED_AT.ge(since)
        if (status != null) conds += AVAILABILITY_JOB_RUN.STATUS.eq(status)
        if (jobId != null) conds += AVAILABILITY_JOB_RUN.JOB_ID.eq(jobId)
        return ctx
            .selectFrom(AVAILABILITY_JOB_RUN)
            .where(if (conds.isEmpty()) org.jooq.impl.DSL.noCondition() else org.jooq.impl.DSL.and(conds))
            .orderBy(AVAILABILITY_JOB_RUN.STARTED_AT.desc(), AVAILABILITY_JOB_RUN.ID.desc())
            .limit(limit.coerceIn(1, 500))
            .fetch { fromRecord(it) }
    }
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityJobRunRepo.kt
git commit -m "AvailabilityJobRunRepo: add listSince for dashboard"
```

---

## Task 3: Add read methods to `AvailabilitySnapshotRepo`

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt`

The existing repo (PR 4) only has `appendBatch`. The dashboard needs `listForReservable` (snapshots tab — newest-first per reservable) and `listForRun` (drill-down from a run row to its snapshots).

- [ ] **Step 1: Add the methods**

Open `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt`. Add a `Snapshot` data class and the two list methods. The existing class only has `SnapshotBatch` and `appendBatch`; we extend it.

Add inside the class, after the existing `appendBatch` method:

```kotlin
    data class Snapshot(
        val id: Long,
        val reservableId: Long?,
        val runId: Long?,
        val targetDate: LocalDate,
        val observedAt: OffsetDateTime,
        val status: String,
        val available: Boolean,
        val dayPayload: String,
    )

    fun listForReservable(
        reservableId: Long,
        limit: Int = 200,
    ): List<Snapshot> =
        ctx
            .selectFrom(AVAILABILITY_SNAPSHOT)
            .where(AVAILABILITY_SNAPSHOT.RESERVABLE_ID.eq(reservableId))
            .orderBy(
                AVAILABILITY_SNAPSHOT.TARGET_DATE.desc(),
                AVAILABILITY_SNAPSHOT.OBSERVED_AT.desc(),
                AVAILABILITY_SNAPSHOT.ID.desc(),
            ).limit(limit.coerceIn(1, 1000))
            .fetch { fromRecord(it) }

    fun listForRun(
        runId: Long,
        limit: Int = 500,
    ): List<Snapshot> =
        ctx
            .selectFrom(AVAILABILITY_SNAPSHOT)
            .where(AVAILABILITY_SNAPSHOT.RUN_ID.eq(runId))
            .orderBy(AVAILABILITY_SNAPSHOT.TARGET_DATE.asc())
            .limit(limit.coerceIn(1, 1000))
            .fetch { fromRecord(it) }

    private fun fromRecord(r: org.jooq.Record): Snapshot =
        Snapshot(
            id = r.get(AVAILABILITY_SNAPSHOT.ID)!!,
            reservableId = r.get(AVAILABILITY_SNAPSHOT.RESERVABLE_ID),
            runId = r.get(AVAILABILITY_SNAPSHOT.RUN_ID),
            targetDate = r.get(AVAILABILITY_SNAPSHOT.TARGET_DATE)!!,
            observedAt = r.get(AVAILABILITY_SNAPSHOT.OBSERVED_AT)!!,
            status = r.get(AVAILABILITY_SNAPSHOT.STATUS)!!,
            available = r.get(AVAILABILITY_SNAPSHOT.AVAILABLE)!!,
            dayPayload = r.get(AVAILABILITY_SNAPSHOT.DAY_PAYLOAD)!!.data(),
        )
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilitySnapshotRepo.kt
git commit -m "AvailabilitySnapshotRepo: add listForReservable, listForRun"
```

---

## Task 4: Dashboard DTOs

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt`

Wire-shape data classes for the three list endpoints + summary. Date/time fields are strings (ISO 8601), matching the convention from `AvailabilityWatchSchemas.kt`.

- [ ] **Step 1: Create the schemas file**

```kotlin
package ca.floo.roadtrip.models.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AvailabilityJobSchema(
    val id: Long,
    @SerialName("watch_id") val watchId: Long,
    @SerialName("cadence_sec") val cadenceSec: Int,
    val status: String,
    @SerialName("next_run_at") val nextRunAt: String,
    @SerialName("claimed_until") val claimedUntil: String? = null,
    @SerialName("last_run_at") val lastRunAt: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("updated_at") val updatedAt: String,
)

@Serializable
data class AvailabilityJobsListResponse(
    val total: Int,
    val limit: Int,
    val offset: Int,
    val jobs: List<AvailabilityJobSchema>,
)

@Serializable
data class AvailabilityJobsSummary(
    val active: Int,
    val paused: Int,
    val done: Int,
    @SerialName("due_now") val dueNow: Int,
    val claimed: Int,
)

@Serializable
data class AvailabilityJobRunSchema(
    val id: Long,
    @SerialName("job_id") val jobId: Long,
    val status: String,
    @SerialName("snapshot_count") val snapshotCount: Int,
    @SerialName("duration_ms") val durationMs: Int? = null,
    val error: String? = null,
    @SerialName("started_at") val startedAt: String,
    @SerialName("completed_at") val completedAt: String? = null,
)

@Serializable
data class AvailabilityJobRunsListResponse(
    val runs: List<AvailabilityJobRunSchema>,
)

@Serializable
data class AvailabilitySnapshotSchema(
    val id: Long,
    @SerialName("reservable_id") val reservableId: Long? = null,
    @SerialName("run_id") val runId: Long? = null,
    @SerialName("target_date") val targetDate: String,
    @SerialName("observed_at") val observedAt: String,
    val status: String,
    val available: Boolean,
)

@Serializable
data class AvailabilitySnapshotsListResponse(
    val snapshots: List<AvailabilitySnapshotSchema>,
)
```

`day_payload` is intentionally NOT in the snapshot schema. The wire response stays compact; if a future view needs the full payload it can be a separate `GET /api/availability/snapshots/{id}` endpoint.

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt
git commit -m "Add availability dashboard DTOs"
```

---

## Task 5: Dashboard routes

**Files:**

- Create: `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutes.kt`

Three GET endpoints. Pattern matches `AvailabilityWatchRoutes.kt` from PR 1.

```
GET /api/availability/jobs?status=&watch_id=&limit=&offset=
GET /api/availability/jobs/summary
GET /api/availability/jobs/{id}/runs?limit=
GET /api/availability/runs?since=&status=&job_id=&limit=
GET /api/availability/snapshots?reservable_id=&run_id=&limit=
```

- [ ] **Step 1: Create the route file**

```kotlin
package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityJobRunSchema
import ca.floo.roadtrip.models.api.AvailabilityJobRunsListResponse
import ca.floo.roadtrip.models.api.AvailabilityJobSchema
import ca.floo.roadtrip.models.api.AvailabilityJobsListResponse
import ca.floo.roadtrip.models.api.AvailabilityJobsSummary
import ca.floo.roadtrip.models.api.AvailabilitySnapshotSchema
import ca.floo.roadtrip.models.api.AvailabilitySnapshotsListResponse
import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityJobRunRepo
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import java.time.OffsetDateTime

private const val DEFAULT_LIST_LIMIT = 100
private const val MAX_LIST_LIMIT = 500
private const val SNAPSHOT_DEFAULT_LIMIT = 200
private const val SNAPSHOT_MAX_LIMIT = 1000

@OptIn(ExperimentalSerializationApi::class)
private val dashboardJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

fun Route.availabilityDashboardRoutes(ctx: DSLContext) {
    val jobs = AvailabilityJobRepo(ctx)
    val runs = AvailabilityJobRunRepo(ctx)
    val snapshots = AvailabilitySnapshotRepo(ctx)

    get("/api/availability/jobs", {
        tags = listOf("availability")
        summary = "List availability polling jobs"
        request {
            queryParameter<String>("status") { description = "active | paused | done" }
            queryParameter<Long>("watch_id") { description = "Filter to one watch's job(s)." }
            queryParameter<Int>("limit") { description = "Page size, default 100, max 500." }
            queryParameter<Int>("offset") { description = "Page offset, default 0." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityJobsListResponse> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val status = call.request.queryParameters["status"]
        val watchId = call.request.queryParameters["watch_id"]?.toLongOrNull()
        val limit =
            (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIST_LIMIT)
                .coerceIn(1, MAX_LIST_LIMIT)
        val offset = call.request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        val rows = jobs.list(status = status, watchId = watchId, limit = limit, offset = offset)
        val total = jobs.count(status = status, watchId = watchId)
        call.respondJson(
            AvailabilityJobsListResponse(
                total = total,
                limit = limit,
                offset = offset,
                jobs = rows.map { it.toSchema() },
            ),
        )
    }

    get("/api/availability/jobs/summary", {
        tags = listOf("availability")
        summary = "Per-status job counters for the dashboard header"
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityJobsSummary> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val s = jobs.summary(OffsetDateTime.now())
        call.respondJson(
            AvailabilityJobsSummary(
                active = s.active,
                paused = s.paused,
                done = s.done,
                dueNow = s.dueNow,
                claimed = s.claimed,
            ),
        )
    }

    get("/api/availability/jobs/{id}/runs", {
        tags = listOf("availability")
        summary = "Runs for one job, newest first"
        request {
            pathParameter<Long>("id") { description = "Job id." }
            queryParameter<Int>("limit") { description = "Page size, default 100, max 500." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityJobRunsListResponse> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
        val limit =
            (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIST_LIMIT)
                .coerceIn(1, MAX_LIST_LIMIT)
        val rows = runs.listForJob(id, limit = limit)
        call.respondJson(AvailabilityJobRunsListResponse(runs = rows.map { it.toSchema() }))
    }

    get("/api/availability/runs", {
        tags = listOf("availability")
        summary = "Recent runs across all jobs"
        request {
            queryParameter<String>("status") { description = "started | completed | failed" }
            queryParameter<Long>("job_id") { description = "Scope to one job." }
            queryParameter<String>("since") { description = "ISO-8601 timestamp; only runs after this." }
            queryParameter<Int>("limit") { description = "Page size, default 100, max 500." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityJobRunsListResponse> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val status = call.request.queryParameters["status"]
        val jobId = call.request.queryParameters["job_id"]?.toLongOrNull()
        val since =
            call.request.queryParameters["since"]?.let {
                runCatching { OffsetDateTime.parse(it) }.getOrNull()
            }
        val limit =
            (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIST_LIMIT)
                .coerceIn(1, MAX_LIST_LIMIT)
        val rows = runs.listSince(since = since, status = status, jobId = jobId, limit = limit)
        call.respondJson(AvailabilityJobRunsListResponse(runs = rows.map { it.toSchema() }))
    }

    get("/api/availability/snapshots", {
        tags = listOf("availability")
        summary = "Snapshot rows filtered by reservable or run"
        request {
            queryParameter<Long>("reservable_id") { description = "Snapshots for this reservable, newest first." }
            queryParameter<Long>("run_id") { description = "Snapshots produced by this run." }
            queryParameter<Int>("limit") { description = "Page size, default 200, max 1000." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilitySnapshotsListResponse> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val reservableId = call.request.queryParameters["reservable_id"]?.toLongOrNull()
        val runId = call.request.queryParameters["run_id"]?.toLongOrNull()
        if ((reservableId == null) == (runId == null)) {
            return@get call.respondError(
                "invalid_filter",
                HttpStatusCode.BadRequest,
                "exactly one of reservable_id or run_id must be set",
            )
        }
        val limit =
            (call.request.queryParameters["limit"]?.toIntOrNull() ?: SNAPSHOT_DEFAULT_LIMIT)
                .coerceIn(1, SNAPSHOT_MAX_LIMIT)
        val rows =
            if (reservableId != null) {
                snapshots.listForReservable(reservableId, limit = limit)
            } else {
                snapshots.listForRun(runId!!, limit = limit)
            }
        call.respondJson(AvailabilitySnapshotsListResponse(snapshots = rows.map { it.toSchema() }))
    }
}

private fun AvailabilityJobRepo.Job.toSchema(): AvailabilityJobSchema =
    AvailabilityJobSchema(
        id = id,
        watchId = watchId,
        cadenceSec = cadenceSec,
        status = status,
        nextRunAt = nextRunAt.toString(),
        claimedUntil = claimedUntil?.toString(),
        lastRunAt = lastRunAt?.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

private fun AvailabilityJobRunRepo.Run.toSchema(): AvailabilityJobRunSchema =
    AvailabilityJobRunSchema(
        id = id,
        jobId = jobId,
        status = status,
        snapshotCount = snapshotCount,
        durationMs = durationMs,
        error = error,
        startedAt = startedAt.toString(),
        completedAt = completedAt?.toString(),
    )

private fun AvailabilitySnapshotRepo.Snapshot.toSchema(): AvailabilitySnapshotSchema =
    AvailabilitySnapshotSchema(
        id = id,
        reservableId = reservableId,
        runId = runId,
        targetDate = targetDate.toString(),
        observedAt = observedAt.toString(),
        status = status,
        available = available,
    )

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(dashboardJson.encodeToString(body), ContentType.Application.Json, status)

private suspend fun ApplicationCall.respondError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    val payload = ApiErrorSchema(error = error, detail = detail)
    respondText(dashboardJson.encodeToString(payload), ContentType.Application.Json, status)
}
```

- [ ] **Step 2: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutes.kt
git commit -m "Add availability dashboard GET routes"
```

---

## Task 6: Wire routes in `Main.kt` + add `/availability` static handler

**Files:**

- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/Main.kt`

- [ ] **Step 1: Add the import**

Find the existing `import ca.floo.roadtrip.routes.availabilityWatchRoutes` line. Add immediately after:

```kotlin
import ca.floo.roadtrip.routes.availabilityDashboardRoutes
```

- [ ] **Step 2: Register the route**

Inside `routing { ... }`, find:

```kotlin
        availabilityWatchRoutes(ctx, availabilityWatchService)
```

Add immediately below:

```kotlin
        availabilityDashboardRoutes(ctx)
```

- [ ] **Step 3: Add static-file handler for `/availability`**

The catalog pages at `/pois`, `/reservables`, `/watches` each have a `get("/foo")` + `get("/foo/")` block that serves the corresponding HTML. Find that block (search for `get("/watches")`) and add immediately below:

```kotlin
        get("/availability") {
            call.respondFile(File(staticDir, "availability.html"))
        }
        get("/availability/") {
            call.respondFile(File(staticDir, "availability.html"))
        }
```

- [ ] **Step 4: Compile**

```bash
cd backend
./gradlew compileKotlin
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/Main.kt
git commit -m "Main: wire availability dashboard routes and /availability static handler"
```

---

## Task 7: `AvailabilityDashboardRoutesTest`

**Files:**

- Create: `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutesTest.kt`

Five tests against Testcontainers Postgres. Mirrors the pattern from `AvailabilityWatchRoutesTest`.

- [ ] **Step 1: Write the test**

```kotlin
package ca.floo.roadtrip.routes

import ca.floo.roadtrip.repo.AvailabilityJobRepo
import ca.floo.roadtrip.repo.AvailabilityJobRunRepo
import ca.floo.roadtrip.repo.migrate
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AvailabilityDashboardRoutesTest {
    private lateinit var pg: PostgreSQLContainer<Nothing>
    private lateinit var ds: HikariDataSource
    private lateinit var ctx: DSLContext

    @BeforeAll
    fun start() {
        val image = DockerImageName.parse("postgis/postgis:16-3.4").asCompatibleSubstituteFor("postgres")
        pg =
            PostgreSQLContainer<Nothing>(image).apply {
                withDatabaseName("roadtrip_test")
                withUsername("test")
                withPassword("test")
            }
        pg.start()
        val cfg =
            HikariConfig().apply {
                jdbcUrl = pg.jdbcUrl
                username = pg.username
                password = pg.password
                maximumPoolSize = 2
            }
        ds = HikariDataSource(cfg)
        migrate(ds)
        ctx = DSL.using(ds, SQLDialect.POSTGRES)
    }

    @AfterAll
    fun stop() {
        ds.close()
        pg.stop()
    }

    @BeforeEach
    fun cleanup() {
        ctx.execute("DELETE FROM availability_snapshot")
        ctx.execute("DELETE FROM availability_job_run")
        ctx.execute("DELETE FROM availability_job")
        ctx.execute("DELETE FROM availability_watch")
        ctx.execute("DELETE FROM reservable_pois")
        ctx.execute("DELETE FROM reservables")
        ctx.execute("DELETE FROM pois")
    }

    private fun seedPoi(): Long =
        ctx
            .fetchOne(
                """
                INSERT INTO pois (
                    source, source_id, category, name, geom, region,
                    properties, provider_ref, fetched_at
                ) VALUES (
                    'test', 'p1', 'campground', 'Upper Pines',
                    ST_SetSRID(ST_MakePoint(-119.56, 37.74), 4326),
                    'CA', '{}'::jsonb, NULL, '2026-06-01 00:00:00+00'::timestamptz
                ) RETURNING id
                """.trimIndent(),
            )!!.get("id", Long::class.java)

    private fun seedJob(poiId: Long): Long {
        val watchId =
            ctx
                .fetchOne(
                    """
                    INSERT INTO availability_watch (
                        poi_id, target_dates, cadence_sec, trigger_kinds
                    ) VALUES (
                        ?, ARRAY['2026-07-04'::date], 60, ARRAY['atc']
                    ) RETURNING id
                    """.trimIndent(),
                    poiId,
                )!!.get("id", Long::class.java)
        return AvailabilityJobRepo(ctx).upsertForWatch(
            watchId = watchId,
            intentPayload = buildJsonObject { put("kind", JsonPrimitive("reservable")) },
            cadenceSec = 60,
            status = "active",
            nextRunAt = OffsetDateTime.now(ZoneOffset.UTC),
        ).id
    }

    @Test
    fun `GET jobs returns the seeded job`() = testApplication {
        application { routing { availabilityDashboardRoutes(ctx) } }
        seedJob(seedPoi())
        val resp = client.get("/api/availability/jobs")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(1, body["total"]!!.jsonPrimitive.int)
        assertEquals(1, body["jobs"]!!.jsonArray.size)
        assertEquals("active", body["jobs"]!!.jsonArray[0].jsonObject["status"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET jobs summary counts by status`() = testApplication {
        application { routing { availabilityDashboardRoutes(ctx) } }
        seedJob(seedPoi())
        val resp = client.get("/api/availability/jobs/summary")
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(1, body["active"]!!.jsonPrimitive.int)
        assertEquals(0, body["paused"]!!.jsonPrimitive.int)
        assertEquals(0, body["done"]!!.jsonPrimitive.int)
    }

    @Test
    fun `GET runs lists runs newest first`() = testApplication {
        application { routing { availabilityDashboardRoutes(ctx) } }
        val jobId = seedJob(seedPoi())
        val runRepo = AvailabilityJobRunRepo(ctx)
        val now = OffsetDateTime.now(ZoneOffset.UTC)
        val older = runRepo.start(jobId, now.minusMinutes(5))
        runRepo.complete(older, snapshotCount = 1, completedAt = now.minusMinutes(4), durationMs = 100)
        val newer = runRepo.start(jobId, now.minusMinutes(1))
        runRepo.complete(newer, snapshotCount = 2, completedAt = now, durationMs = 100)
        val resp = client.get("/api/availability/runs")
        assertEquals(HttpStatusCode.OK, resp.status)
        val rows = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["runs"]!!.jsonArray
        assertEquals(2, rows.size)
        assertEquals(newer, rows[0].jsonObject["id"]!!.jsonPrimitive.long)
        assertEquals(older, rows[1].jsonObject["id"]!!.jsonPrimitive.long)
    }

    @Test
    fun `GET runs filters by job_id`() = testApplication {
        application { routing { availabilityDashboardRoutes(ctx) } }
        val poiId = seedPoi()
        val jobA = seedJob(poiId)
        val jobB =
            AvailabilityJobRepo(ctx).upsertForWatch(
                watchId =
                    ctx
                        .fetchOne(
                            """
                            INSERT INTO availability_watch (
                                poi_id, target_dates, cadence_sec, trigger_kinds
                            ) VALUES (
                                ?, ARRAY['2026-07-04'::date], 60, ARRAY['atc']
                            ) RETURNING id
                            """.trimIndent(),
                            poiId,
                        )!!.get("id", Long::class.java),
                intentPayload = buildJsonObject { put("kind", JsonPrimitive("reservable")) },
                cadenceSec = 60,
                status = "active",
                nextRunAt = OffsetDateTime.now(ZoneOffset.UTC),
            ).id
        val runRepo = AvailabilityJobRunRepo(ctx)
        runRepo.start(jobA, OffsetDateTime.now(ZoneOffset.UTC))
        runRepo.start(jobB, OffsetDateTime.now(ZoneOffset.UTC))
        val resp = client.get("/api/availability/runs?job_id=$jobA")
        val rows = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["runs"]!!.jsonArray
        assertEquals(1, rows.size)
        assertEquals(jobA, rows[0].jsonObject["job_id"]!!.jsonPrimitive.long)
    }

    @Test
    fun `GET snapshots requires exactly one filter`() = testApplication {
        application { routing { availabilityDashboardRoutes(ctx) } }
        val resp = client.get("/api/availability/snapshots")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        val body = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals("invalid_filter", body["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun `GET jobs id runs returns 400 on invalid id`() = testApplication {
        application { routing { availabilityDashboardRoutes(ctx) } }
        val resp = client.get("/api/availability/jobs/not-a-number/runs")
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }
}
```

- [ ] **Step 2: Run tests**

```bash
cd backend
./gradlew --stop
./gradlew test --tests AvailabilityDashboardRoutesTest
```

Expected: 6/6 passing.

- [ ] **Step 3: Commit**

```bash
git add backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutesTest.kt
git commit -m "AvailabilityDashboardRoutesTest"
```

---

## Task 8: API client `web/api/availability-dashboard-api.js`

**Files:**

- Create: `web/api/availability-dashboard-api.js`

```javascript
// Client for /api/availability/jobs|runs|snapshots GETs.
// Read-only — no mutations from the dashboard.

import { jsonGetOk } from './http.js';

export function listJobs({ status, watchId, limit, offset, signal } = {}) {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (watchId != null && watchId !== '') qs.set('watch_id', watchId);
  if (limit != null) qs.set('limit', limit);
  if (offset != null) qs.set('offset', offset);
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`/api/availability/jobs${suffix}`, { signal });
}

export function getJobsSummary({ signal } = {}) {
  return jsonGetOk('/api/availability/jobs/summary', { signal });
}

export function listRunsForJob(jobId, { limit, signal } = {}) {
  const qs = new URLSearchParams();
  if (limit != null) qs.set('limit', limit);
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`/api/availability/jobs/${encodeURIComponent(jobId)}/runs${suffix}`, { signal });
}

export function listRuns({ status, jobId, since, limit, signal } = {}) {
  const qs = new URLSearchParams();
  if (status) qs.set('status', status);
  if (jobId != null && jobId !== '') qs.set('job_id', jobId);
  if (since) qs.set('since', since);
  if (limit != null) qs.set('limit', limit);
  const suffix = qs.toString() ? `?${qs}` : '';
  return jsonGetOk(`/api/availability/runs${suffix}`, { signal });
}

export function listSnapshotsForReservable(reservableId, { limit, signal } = {}) {
  const qs = new URLSearchParams({ reservable_id: String(reservableId) });
  if (limit != null) qs.set('limit', limit);
  return jsonGetOk(`/api/availability/snapshots?${qs}`, { signal });
}

export function listSnapshotsForRun(runId, { limit, signal } = {}) {
  const qs = new URLSearchParams({ run_id: String(runId) });
  if (limit != null) qs.set('limit', limit);
  return jsonGetOk(`/api/availability/snapshots?${qs}`, { signal });
}
```

- [ ] **Step 1: Create the file**

(Content above.)

- [ ] **Step 2: Syntax check**

```bash
node --check web/api/availability-dashboard-api.js
```

Expected: no output (success).

- [ ] **Step 3: Commit**

```bash
git add web/api/availability-dashboard-api.js
git commit -m "Add availability dashboard API client"
```

---

## Task 9: Jobs tab module

**Files:**

- Create: `web/components/availability/jobs-tab.js`

The tab module exports `mount(rootEl)` which renders into the given element and wires its own event handlers. The page bootstrap calls `mount` for the active tab.

- [ ] **Step 1: Create the directory + file**

```bash
mkdir -p web/components/availability
```

Then write `web/components/availability/jobs-tab.js`:

```javascript
// Jobs tab: read-only list with per-status counters at the top.
// Provenance: clicking a job's id navigates to /availability?tab=runs&job_id={id}.
// Clicking a job's watch_id navigates to /watches (filtering by status not yet
// supported there; we just go to the page).

import { listJobs, getJobsSummary } from '/web/api/availability-dashboard-api.js';

export async function mount(rootEl, { onTabSwitch }) {
  rootEl.innerHTML = `
    <section class="panel">
      <div class="form-stack" id="jobs-counters">Loading…</div>
    </section>
    <section class="panel">
      <h2>Filter</h2>
      <form id="jobs-filter" class="filters">
        <label>Status
          <select name="status">
            <option value="">any</option>
            <option value="active" selected>active</option>
            <option value="paused">paused</option>
            <option value="done">done</option>
          </select>
        </label>
        <label>Watch ID <input name="watch_id" inputmode="numeric"></label>
        <div class="actions">
          <button class="primary" type="submit">Apply</button>
          <button type="reset">Reset</button>
        </div>
      </form>
    </section>
    <section class="panel" aria-live="polite">
      <div id="jobs-status" class="status">Loading…</div>
      <div id="jobs-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#jobs-filter');
  const statusEl = rootEl.querySelector('#jobs-status');
  const resultsEl = rootEl.querySelector('#jobs-results');
  const countersEl = rootEl.querySelector('#jobs-counters');

  filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
  });
  filterForm.addEventListener('reset', () => setTimeout(refresh, 0));

  resultsEl.addEventListener('click', (e) => {
    const link = e.target.closest('[data-action]');
    if (!link) return;
    const action = link.dataset.action;
    if (action === 'goto-runs-for-job') {
      e.preventDefault();
      onTabSwitch('runs', { job_id: link.dataset.jobId });
    }
  });

  await Promise.all([refreshSummary(), refresh()]);

  async function refreshSummary() {
    try {
      const s = await getJobsSummary();
      countersEl.innerHTML = `
        <div>active: <strong>${s.active}</strong></div>
        <div>paused: <strong>${s.paused}</strong></div>
        <div>done: <strong>${s.done}</strong></div>
        <div>due now: <strong>${s.due_now}</strong></div>
        <div>claimed: <strong>${s.claimed}</strong></div>
      `;
    } catch (err) {
      countersEl.textContent = `Counters error: ${err.message}`;
    }
  }

  async function refresh() {
    const fd = new FormData(filterForm);
    const params = {
      status: fd.get('status') || undefined,
      watchId: fd.get('watch_id') || undefined,
    };
    statusEl.textContent = 'Loading…';
    try {
      const data = await listJobs(params);
      statusEl.textContent = `${data.total} job${data.total === 1 ? '' : 's'}.`;
      render(data.jobs);
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
    }
  }

  function render(jobs) {
    if (jobs.length === 0) {
      resultsEl.innerHTML = '<div class="empty">No jobs.</div>';
      return;
    }
    resultsEl.innerHTML = `
      <table class="data-table">
        <thead><tr>
          <th>id</th><th>watch</th><th>cadence</th><th>status</th>
          <th>next run</th><th>last run</th><th>claimed</th>
        </tr></thead>
        <tbody>
          ${jobs.map(renderRow).join('')}
        </tbody>
      </table>
    `;
  }

  function renderRow(j) {
    return `
      <tr>
        <td>
          <a href="#" data-action="goto-runs-for-job" data-job-id="${escapeHtml(j.id)}">${escapeHtml(j.id)}</a>
        </td>
        <td>
          <a href="/watches?id=${encodeURIComponent(j.watch_id)}">#${escapeHtml(j.watch_id)}</a>
        </td>
        <td>${escapeHtml(j.cadence_sec)}s</td>
        <td>${escapeHtml(j.status)}</td>
        <td>${escapeHtml(formatTimestamp(j.next_run_at))}</td>
        <td>${escapeHtml(j.last_run_at ? formatTimestamp(j.last_run_at) : '—')}</td>
        <td>${j.claimed_until ? escapeHtml(formatTimestamp(j.claimed_until)) : '—'}</td>
      </tr>
    `;
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function formatTimestamp(iso) {
  // 2026-06-15T09:14:48.123Z → 2026-06-15 09:14:48
  return iso.replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '');
}
```

- [ ] **Step 2: Syntax check**

```bash
node --check web/components/availability/jobs-tab.js
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add web/components/availability/jobs-tab.js
git commit -m "Add availability dashboard Jobs tab"
```

---

## Task 10: Runs tab module

**Files:**

- Create: `web/components/availability/runs-tab.js`

```javascript
// Runs tab: recent executions across all jobs (or filtered to one job).
// Provenance: clicking a run id navigates to /availability?tab=snapshots&run_id={id}.
// Clicking a run's job_id filters this tab to that job (URL update + re-fetch).

import { listRuns } from '/web/api/availability-dashboard-api.js';

export async function mount(rootEl, { onTabSwitch, urlParams }) {
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Filter</h2>
      <form id="runs-filter" class="filters">
        <label>Status
          <select name="status">
            <option value="">any</option>
            <option value="started">started</option>
            <option value="completed">completed</option>
            <option value="failed">failed</option>
          </select>
        </label>
        <label>Job ID <input name="job_id" inputmode="numeric"></label>
        <div class="actions">
          <button class="primary" type="submit">Apply</button>
          <button type="reset">Reset</button>
        </div>
      </form>
    </section>
    <section class="panel" aria-live="polite">
      <div id="runs-status" class="status">Loading…</div>
      <div id="runs-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#runs-filter');
  const statusEl = rootEl.querySelector('#runs-status');
  const resultsEl = rootEl.querySelector('#runs-results');

  // Prefill from URL params (drill-down from jobs tab).
  if (urlParams.job_id) filterForm.querySelector('[name=job_id]').value = urlParams.job_id;
  if (urlParams.status) filterForm.querySelector('[name=status]').value = urlParams.status;

  filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
  });
  filterForm.addEventListener('reset', () => setTimeout(refresh, 0));

  resultsEl.addEventListener('click', (e) => {
    const link = e.target.closest('[data-action]');
    if (!link) return;
    e.preventDefault();
    if (link.dataset.action === 'goto-snapshots-for-run') {
      onTabSwitch('snapshots', { run_id: link.dataset.runId });
    } else if (link.dataset.action === 'filter-by-job') {
      filterForm.querySelector('[name=job_id]').value = link.dataset.jobId;
      refresh();
    }
  });

  await refresh();

  async function refresh() {
    const fd = new FormData(filterForm);
    const params = {
      status: fd.get('status') || undefined,
      jobId: fd.get('job_id') || undefined,
    };
    statusEl.textContent = 'Loading…';
    try {
      const data = await listRuns(params);
      statusEl.textContent = `${data.runs.length} run${data.runs.length === 1 ? '' : 's'}.`;
      render(data.runs);
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
    }
  }

  function render(runs) {
    if (runs.length === 0) {
      resultsEl.innerHTML = '<div class="empty">No runs.</div>';
      return;
    }
    resultsEl.innerHTML = `
      <table class="data-table">
        <thead><tr>
          <th>id</th><th>job</th><th>status</th><th>snapshots</th>
          <th>duration</th><th>started</th><th>error</th>
        </tr></thead>
        <tbody>
          ${runs.map(renderRow).join('')}
        </tbody>
      </table>
    `;
  }

  function renderRow(r) {
    return `
      <tr>
        <td>
          <a href="#" data-action="goto-snapshots-for-run" data-run-id="${escapeHtml(r.id)}">${escapeHtml(r.id)}</a>
        </td>
        <td>
          <a href="#" data-action="filter-by-job" data-job-id="${escapeHtml(r.job_id)}">#${escapeHtml(r.job_id)}</a>
        </td>
        <td>${escapeHtml(r.status)}</td>
        <td>${escapeHtml(r.snapshot_count)}</td>
        <td>${r.duration_ms != null ? `${escapeHtml(r.duration_ms)}ms` : '—'}</td>
        <td>${escapeHtml(formatTimestamp(r.started_at))}</td>
        <td>${r.error ? escapeHtml(truncate(r.error, 80)) : ''}</td>
      </tr>
    `;
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function formatTimestamp(iso) {
  return iso.replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '');
}

function truncate(s, n) {
  return s.length > n ? `${s.slice(0, n - 1)}…` : s;
}
```

- [ ] **Step 1: Create the file**

(Content above.)

- [ ] **Step 2: Syntax check**

```bash
node --check web/components/availability/runs-tab.js
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add web/components/availability/runs-tab.js
git commit -m "Add availability dashboard Runs tab"
```

---

## Task 11: Snapshots tab module

**Files:**

- Create: `web/components/availability/snapshots-tab.js`

```javascript
// Snapshots tab: tabular view scoped to either one reservable or one run.
// Backend rejects calls with neither/both, so the tab requires the operator
// to pick one mode.

import {
  listSnapshotsForReservable,
  listSnapshotsForRun,
} from '/web/api/availability-dashboard-api.js';

export async function mount(rootEl, { urlParams }) {
  rootEl.innerHTML = `
    <section class="panel">
      <h2>Filter</h2>
      <form id="snap-filter" class="filters">
        <label>Reservable ID <input name="reservable_id" inputmode="numeric"></label>
        <label>Run ID <input name="run_id" inputmode="numeric"></label>
        <div class="actions">
          <button class="primary" type="submit">Apply</button>
          <button type="reset">Reset</button>
        </div>
      </form>
    </section>
    <section class="panel" aria-live="polite">
      <div id="snap-status" class="status">Set a Reservable ID or Run ID to load snapshots.</div>
      <div id="snap-results"></div>
    </section>
  `;

  const filterForm = rootEl.querySelector('#snap-filter');
  const statusEl = rootEl.querySelector('#snap-status');
  const resultsEl = rootEl.querySelector('#snap-results');

  if (urlParams.reservable_id) filterForm.querySelector('[name=reservable_id]').value = urlParams.reservable_id;
  if (urlParams.run_id) filterForm.querySelector('[name=run_id]').value = urlParams.run_id;

  filterForm.addEventListener('submit', (e) => {
    e.preventDefault();
    refresh();
  });
  filterForm.addEventListener('reset', () => setTimeout(refresh, 0));

  if (urlParams.reservable_id || urlParams.run_id) {
    await refresh();
  }

  async function refresh() {
    const fd = new FormData(filterForm);
    const reservableId = (fd.get('reservable_id') || '').trim();
    const runId = (fd.get('run_id') || '').trim();
    if (!reservableId === !runId) {
      statusEl.textContent = 'Set exactly one of Reservable ID or Run ID.';
      resultsEl.innerHTML = '';
      return;
    }
    statusEl.textContent = 'Loading…';
    try {
      const data = reservableId
        ? await listSnapshotsForReservable(reservableId)
        : await listSnapshotsForRun(runId);
      statusEl.textContent = `${data.snapshots.length} snapshot${data.snapshots.length === 1 ? '' : 's'}.`;
      render(data.snapshots);
    } catch (err) {
      statusEl.textContent = `Error: ${err.message}`;
      resultsEl.innerHTML = '';
    }
  }

  function render(snaps) {
    if (snaps.length === 0) {
      resultsEl.innerHTML = '<div class="empty">No snapshots.</div>';
      return;
    }
    resultsEl.innerHTML = `
      <table class="data-table">
        <thead><tr>
          <th>id</th><th>reservable</th><th>run</th><th>target date</th>
          <th>observed</th><th>status</th><th>available</th>
        </tr></thead>
        <tbody>
          ${snaps.map(renderRow).join('')}
        </tbody>
      </table>
    `;
  }

  function renderRow(s) {
    return `
      <tr>
        <td>${escapeHtml(s.id)}</td>
        <td>${s.reservable_id != null ? `<a href="/reservables?id=${encodeURIComponent(s.reservable_id)}">#${escapeHtml(s.reservable_id)}</a>` : '—'}</td>
        <td>${s.run_id != null ? `#${escapeHtml(s.run_id)}` : '—'}</td>
        <td>${escapeHtml(s.target_date)}</td>
        <td>${escapeHtml(formatTimestamp(s.observed_at))}</td>
        <td>${escapeHtml(s.status)}</td>
        <td>${s.available ? '✓' : '✗'}</td>
      </tr>
    `;
  }
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]
  ));
}

function formatTimestamp(iso) {
  return iso.replace('T', ' ').replace(/\.\d+/, '').replace(/Z$/, '');
}
```

- [ ] **Step 1: Create the file**

(Content above.)

- [ ] **Step 2: Syntax check**

```bash
node --check web/components/availability/snapshots-tab.js
```

Expected: no output.

- [ ] **Step 3: Commit**

```bash
git add web/components/availability/snapshots-tab.js
git commit -m "Add availability dashboard Snapshots tab"
```

---

## Task 12: Page bootstrap + HTML shell

**Files:**

- Create: `availability.html` (repo root)
- Create: `web/availability.js`

The page reads `?tab=jobs|runs|snapshots` from the URL (default `jobs`), mounts the active tab, and updates the URL on tab clicks. URL params for filters (`job_id`, `run_id`, `reservable_id`) round-trip — clicking a drill-down link both navigates the active tab AND updates the URL so the page is bookmarkable.

- [ ] **Step 1: Create `availability.html`**

The page mirrors the styling pattern from `watches.html`. Inline `<style>` block defines CSS variables + layout primitives that `catalog.css` doesn't supply (matching the existing pattern across catalog pages).

```html
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="theme-color" content="#26272d">
<title>Availability Dashboard</title>
<style>
  :root {
    --surface: #26272d;
    --bg: #1c1d21;
    --bg-subtle: rgba(255,255,255,0.04);
    --bg-hover: rgba(255,255,255,0.06);
    --border: rgba(255,255,255,0.08);
    --border-strong: rgba(255,255,255,0.14);
    --text: #e6e8eb;
    --muted: #8a8f96;
    --faint: #5a6068;
    --accent: #4cb96a;
    --accent-hover: #62cc7d;
    --error: #f56565;
    --catalog-table-min-width: 900px;
    --catalog-table-layout: auto;
    --name-max-width: 360px;
    --name-min-width: 220px;
    --links-min-width: 220px;
    --result-label-width: 92px;
  }
  * { box-sizing: border-box; }
  html, body {
    margin: 0;
    min-height: 100%;
    background: var(--bg);
    color: var(--text);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
  }
  body { padding: 20px; }
  a { color: var(--accent); text-decoration: none; }
  a:hover { text-decoration: underline; }
  .shell { max-width: 1280px; margin: 0 auto; }
  .top {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 16px;
    margin-bottom: 16px;
  }
  h1 { margin: 0; font-size: 22px; line-height: 1.2; font-weight: 650; }
  .sub { margin-top: 4px; color: var(--muted); font-size: 13px; }
  .nav {
    display: flex;
    align-items: center;
    gap: 10px 16px;
    flex-wrap: wrap;
    justify-content: flex-end;
  }
  .nav-group { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
  .nav-group + .nav-group {
    padding-left: 14px;
    border-left: 1px solid var(--border-strong);
  }
  .nav a, button {
    border: 1px solid var(--border-strong);
    background: var(--bg-subtle);
    color: var(--text);
    border-radius: 8px;
    height: 34px;
    padding: 0 12px;
    font: inherit;
    font-size: 13px;
    cursor: pointer;
    display: inline-flex;
    align-items: center;
    justify-content: center;
  }
  .nav a:hover, button:hover {
    background: var(--bg-hover);
    text-decoration: none;
  }
  .nav a[aria-current="page"] {
    border-color: rgba(76,185,106,0.45);
    background: rgba(76,185,106,0.12);
    color: #87d99b;
  }
  .nav a.outside-link { gap: 7px; }
  .nav a.outside-link::after {
    content: "";
    width: 10px;
    height: 10px;
    border-top: 1.5px solid currentColor;
    border-right: 1.5px solid currentColor;
    opacity: 0.7;
  }
  .tabs {
    display: flex;
    gap: 6px;
    margin-bottom: 14px;
    border-bottom: 1px solid var(--border);
  }
  .tabs a {
    padding: 8px 14px;
    border: 1px solid transparent;
    border-bottom: none;
    border-radius: 8px 8px 0 0;
    background: transparent;
    color: var(--muted);
    font-size: 13px;
  }
  .tabs a:hover { color: var(--text); text-decoration: none; }
  .tabs a[aria-current="page"] {
    color: var(--text);
    background: var(--surface);
    border-color: var(--border);
  }
  .filters {
    display: flex;
    flex-wrap: wrap;
    gap: 10px 20px;
    align-items: flex-end;
  }
  .filters > label,
  .filters > .actions { flex: 3 1 200px; }
  .form-stack {
    display: flex;
    flex-wrap: wrap;
    gap: 10px 20px;
    align-items: center;
  }
  .form-stack > div { flex: 0 0 auto; padding: 4px 12px; border-radius: 6px; background: var(--bg-subtle); }
  .data-table { width: 100%; border-collapse: collapse; font-size: 13px; }
  .data-table th, .data-table td {
    padding: 8px 10px;
    text-align: left;
    border-bottom: 1px solid var(--border);
    vertical-align: middle;
  }
  .data-table th {
    color: var(--muted);
    font-weight: 600;
    text-transform: uppercase;
    letter-spacing: 0.04em;
    font-size: 11px;
  }
  .empty { color: var(--muted); padding: 14px; text-align: center; }
  .panel { padding: 14px; margin-bottom: 14px; }
  .panel h2 {
    margin: 0 0 10px;
    font-size: 14px;
    color: var(--muted);
    text-transform: uppercase;
    letter-spacing: 0.04em;
  }
  @media (max-width: 640px) {
    body { padding: 12px; }
    .top, .status { align-items: flex-start; flex-direction: column; }
    .nav { justify-content: flex-start; }
    .nav-group + .nav-group {
      width: 100%;
      padding-left: 0;
      padding-top: 8px;
      border-left: 0;
      border-top: 1px solid var(--border-strong);
    }
  }
</style>
<link rel="stylesheet" href="/web/components/catalog.css">
</head>
<body>
<main class="shell">
  <header class="top">
    <div>
      <h1>Availability Dashboard</h1>
      <div class="sub">Live view of polling jobs, recent runs, and observed snapshots.</div>
    </div>
    <nav class="nav" aria-label="Page links">
      <div class="nav-group" aria-label="Catalog pages">
        <a href="/pois">POIs</a>
        <a href="/reservables">Reservables</a>
        <a href="/watches">Watches</a>
        <a href="/availability" aria-current="page">Activity</a>
      </div>
      <div class="nav-group" aria-label="Outside links">
        <a class="outside-link" href="/">Map</a>
        <a class="outside-link" href="/api/docs">API docs</a>
      </div>
    </nav>
  </header>

  <nav class="tabs" aria-label="Dashboard tabs">
    <a href="?tab=jobs" data-tab="jobs">Jobs</a>
    <a href="?tab=runs" data-tab="runs">Runs</a>
    <a href="?tab=snapshots" data-tab="snapshots">Snapshots</a>
  </nav>

  <div id="tab-root"></div>
</main>
<script type="module" src="/web/availability.js"></script>
</body>
</html>
```

- [ ] **Step 2: Create `web/availability.js`**

```javascript
import { mount as mountJobs } from '/web/components/availability/jobs-tab.js';
import { mount as mountRuns } from '/web/components/availability/runs-tab.js';
import { mount as mountSnapshots } from '/web/components/availability/snapshots-tab.js';

const TABS = {
  jobs: mountJobs,
  runs: mountRuns,
  snapshots: mountSnapshots,
};

const tabRoot = document.getElementById('tab-root');
const tabLinks = document.querySelectorAll('.tabs a[data-tab]');

tabLinks.forEach((a) => {
  a.addEventListener('click', (e) => {
    e.preventDefault();
    const tab = a.dataset.tab;
    setTab(tab, {});
  });
});

const initial = readUrlState();
setTab(initial.tab, initial.params);

function readUrlState() {
  const qs = new URLSearchParams(window.location.search);
  const tab = qs.get('tab') || 'jobs';
  const params = {};
  for (const [k, v] of qs) {
    if (k !== 'tab') params[k] = v;
  }
  return { tab: TABS[tab] ? tab : 'jobs', params };
}

function setTab(tab, params) {
  if (!TABS[tab]) return;
  const qs = new URLSearchParams({ tab });
  for (const [k, v] of Object.entries(params)) {
    if (v != null && v !== '') qs.set(k, v);
  }
  window.history.replaceState(null, '', `/availability?${qs}`);
  tabLinks.forEach((a) => {
    if (a.dataset.tab === tab) a.setAttribute('aria-current', 'page');
    else a.removeAttribute('aria-current');
  });
  tabRoot.innerHTML = '';
  TABS[tab](tabRoot, {
    onTabSwitch: (nextTab, nextParams) => setTab(nextTab, nextParams || {}),
    urlParams: params,
  });
}
```

- [ ] **Step 3: Syntax check**

```bash
node --check web/availability.js
```

Expected: no output.

- [ ] **Step 4: Commit**

```bash
git add availability.html web/availability.js
git commit -m "Add /availability dashboard page shell"
```

---

## Task 13: Add `Activity` link to existing catalog navs + bind-mount HTML

**Files:**

- Modify: `pois.html`, `reservables.html`, `watches.html`, `docker-compose.yml`

The existing nav blocks have `<a href="/pois">POIs</a> <a href="/reservables">Reservables</a> <a href="/watches">Watches</a>`. Add a fourth: `<a href="/availability">Activity</a>`. Update `docker-compose.yml` so the new HTML is bind-mounted.

- [ ] **Step 1: Update `pois.html`**

Find:

```html
        <a href="/watches">Watches</a>
```

Add immediately below:

```html
        <a href="/availability">Activity</a>
```

- [ ] **Step 2: Update `reservables.html`**

Same edit.

- [ ] **Step 3: Update `watches.html`**

Same edit.

- [ ] **Step 4: Update `docker-compose.yml`**

Find the existing volumes block:

```yaml
      - ./watches.html:/app/static/watches.html:ro
```

Add immediately below:

```yaml
      - ./availability.html:/app/static/availability.html:ro
```

Also update the comment block at the top of the backend service if it lists pages (search for `/watches` to find any documentation comment that should mention `/availability`).

- [ ] **Step 5: Commit**

```bash
git add pois.html reservables.html watches.html docker-compose.yml
git commit -m "Add /availability link to catalog navs and docker-compose mount"
```

---

## Task 14: Lint, full tests, push

- [ ] **Step 1: Format + check**

```bash
cd backend
./gradlew --stop
./gradlew ktlintFormat
./gradlew ktlintCheck
```

Expected: green.

- [ ] **Step 2: Full test suite**

```bash
./gradlew test --rerun-tasks 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL with `AvailabilityDashboardRoutesTest` 6/6 passing alongside the existing suite.

- [ ] **Step 3: Commit any ktlintFormat changes**

```bash
cd /Users/wc/code/github/wwchen/roadtrip
git add backend/src
git commit -m "ktlintFormat" || true
```

- [ ] **Step 4: Add the plan doc**

```bash
git add docs/superpowers/plans/2026-06-15-pr5-availability-dashboard.md
git commit -m "Add availability dashboard plan"
```

- [ ] **Step 5: Push and open the stacked PR**

```bash
git push -u origin avail-dashboard
```

Write the body to a file and create the PR:

```bash
cat > pr_body.md <<'PR'
## Availability operator dashboard

Stacks on PR #229. Adds `/availability` — a three-tab read-only operator view of jobs, runs, and snapshots. Closes the gap that `/availability` previously fell through to the static-file fallback (the map).

### What ships
- **Backend:** five new GET endpoints under `/api/availability/*` (`jobs`, `jobs/summary`, `jobs/{id}/runs`, `runs`, `snapshots`). Three new repo methods plus `Summary` for the per-status counter row.
- **Frontend:** `availability.html` + `web/availability.js` + three tab modules under `web/components/availability/`. Tabs live in URL state (`?tab=jobs|runs|snapshots&filter=…`), so dashboards are bookmarkable and drill-down links are real navigations.
- **Tests:** `AvailabilityDashboardRoutesTest` covers all five endpoints + invalid-input paths.
- **Wiring:** Activity link added to POIs/Reservables/Watches navs; `availability.html` mounted in `docker-compose.yml`.

### Scope
- Read-only. Operators pause/resume via `/watches`; the dashboard never mutates jobs.
- Snapshots tab is tabular only. No heatmap (deferrable; the spec mockup version was option B).
- No SSE/poll-based auto-refresh. Reload the page; counter row also refreshes per page mount.

### Verification
- `./gradlew ktlintCheck test` — green
- Manual: `tilt up`, create a watch, wait one cadence, open `/availability` and confirm the new job appears, click into runs, then into snapshots.

🤖 Generated with [Claude Code](https://claude.com/claude-code)
PR

gh pr create --title "Availability operator dashboard" --body-file pr_body.md --base avail-snapshots --repo wwchen/roadtrip
rm pr_body.md
```

- [ ] **Step 6: Verify CI**

```bash
gh pr checks
```

Expected: green.

---

## Out of scope (deferred)

- **Per-job force-poll button.** A `POST /api/availability/jobs/{id}/poll-now` that bumps `next_run_at` to `now()` is a useful operator hammer when investigating a stuck job. Not in this PR; add when first needed.
- **Snapshots heatmap.** Reservable × dates color-coded grid. Higher production-quality dashboard piece; deferred until the tabular view feels insufficient.
- **Auto-refresh.** No SSE / polling on the dashboard itself. Operators reload to re-fetch.
- **Pagination on the runs tab.** Currently capped at 100 (max 500). If runs accumulate enough that the cap matters, add `since=` UI + an offset/cursor. Currently relying on the `since=` query parameter being usable manually.
- **Snapshot detail view.** No `GET /api/availability/snapshots/{id}` for the full `day_payload`. Add when the dashboard's tabular view leaves a real question unanswered.
