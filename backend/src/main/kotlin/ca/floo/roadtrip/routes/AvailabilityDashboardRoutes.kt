package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityJobRunSchema
import ca.floo.roadtrip.models.api.AvailabilityJobRunsListResponse
import ca.floo.roadtrip.models.api.AvailabilityJobSchema
import ca.floo.roadtrip.models.api.AvailabilityJobsListResponse
import ca.floo.roadtrip.models.api.AvailabilityJobsSummary
import ca.floo.roadtrip.models.api.AvailabilitySnapshotSchema
import ca.floo.roadtrip.models.api.AvailabilitySnapshotStatsSchema
import ca.floo.roadtrip.models.api.AvailabilitySnapshotsListResponse
import ca.floo.roadtrip.models.api.AvailabilitySnapshotsSummaryResponse
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
    val reservables =
        ca.floo.roadtrip.repo
            .ReservableRepo(ctx)

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
        val offset =
            call.request.queryParameters["offset"]
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
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
        summary = "Snapshot rows filtered by reservable rid or run id"
        request {
            queryParameter<String>("reservable_rid") {
                description =
                    "Snapshots for this reservable (e.g. site:recgov:330257), newest first."
            }
            queryParameter<Long>("run_id") { description = "Snapshots produced by this run." }
            queryParameter<Int>("limit") { description = "Page size, default 200, max 1000." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilitySnapshotsListResponse> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val rid = call.request.queryParameters["reservable_rid"]?.takeIf { it.isNotBlank() }
        val runId = call.request.queryParameters["run_id"]?.toLongOrNull()
        if ((rid == null) == (runId == null)) {
            return@get call.respondError(
                "invalid_filter",
                HttpStatusCode.BadRequest,
                "exactly one of reservable_rid or run_id must be set",
            )
        }
        val limit =
            (call.request.queryParameters["limit"]?.toIntOrNull() ?: SNAPSHOT_DEFAULT_LIMIT)
                .coerceIn(1, SNAPSHOT_MAX_LIMIT)
        val rows =
            if (rid != null) {
                val parsed =
                    ca.floo.roadtrip.models.ReservableId
                        .parse(rid)
                        ?: return@get call.respondError(
                            "invalid_reservable_rid",
                            HttpStatusCode.BadRequest,
                            "could not parse reservable_rid '$rid'",
                        )
                val reservable =
                    reservables.findByRid(parsed)
                        ?: return@get call.respondError(
                            "reservable_not_found",
                            HttpStatusCode.NotFound,
                            "no reservable with rid $rid",
                        )
                snapshots.listForReservable(reservable.id, limit = limit)
            } else {
                snapshots.listForRun(runId!!, limit = limit)
            }
        call.respondJson(AvailabilitySnapshotsListResponse(snapshots = rows.map { it.toSchema() }))
    }

    get("/api/availability/snapshots/summary", {
        tags = listOf("availability")
        summary = "Per-date stats for one reservable's snapshot history"
        request {
            queryParameter<String>("reservable_rid") { description = "Reservable composite id (e.g. site:recgov:330257)." }
            queryParameter<String>("dates") {
                description = "Comma-separated YYYY-MM-DD list. If omitted, every date with snapshots in the window is returned."
            }
            queryParameter<Int>("window_hours") { description = "Snapshot window in hours, default 168 (7 days)." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilitySnapshotsSummaryResponse> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val rid =
            call.request.queryParameters["reservable_rid"]?.takeIf { it.isNotBlank() }
                ?: return@get call.respondError(
                    "missing_reservable_rid",
                    HttpStatusCode.BadRequest,
                    "reservable_rid is required",
                )
        val parsed =
            ca.floo.roadtrip.models.ReservableId
                .parse(rid)
                ?: return@get call.respondError(
                    "invalid_reservable_rid",
                    HttpStatusCode.BadRequest,
                    "could not parse reservable_rid '$rid'",
                )
        val reservable =
            reservables.findByRid(parsed)
                ?: return@get call.respondError(
                    "reservable_not_found",
                    HttpStatusCode.NotFound,
                    "no reservable with rid $rid",
                )
        val windowHours =
            call.request.queryParameters["window_hours"]
                ?.toIntOrNull()
                ?.coerceIn(1, 24 * 30) ?: (24 * 7)
        val explicitDates =
            call.request.queryParameters["dates"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
                .orEmpty()
        val dates =
            explicitDates.ifEmpty {
                // Discover distinct dates that have any snapshot in the window.
                val windowStart =
                    java.time.OffsetDateTime
                        .now()
                        .minusHours(windowHours.toLong())
                ctx
                    .selectDistinct(ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT.TARGET_DATE)
                    .from(ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT)
                    .where(
                        ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT.RESERVABLE_ID
                            .eq(reservable.id),
                    ).and(
                        ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT.OBSERVED_AT
                            .ge(windowStart),
                    ).orderBy(
                        ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT.TARGET_DATE
                            .asc(),
                    ).fetch { it.value1() }
            }
        val stats = snapshots.summarize(reservable.id, dates, windowHours = windowHours)
        call.respondJson(
            AvailabilitySnapshotsSummaryResponse(
                reservableRid = rid,
                stats = stats.map { it.toSchema() },
            ),
        )
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

private fun AvailabilitySnapshotRepo.TargetDateStats.toSchema(): AvailabilitySnapshotStatsSchema =
    AvailabilitySnapshotStatsSchema(
        targetDate = targetDate.toString(),
        totalSnapshots = totalSnapshots,
        lastOpenAt = lastOpenAt?.toString(),
        isCurrentlyOpen = isCurrentlyOpen,
        currentOrLastOpenWindowSec = currentOrLastOpenWindowSec,
        medianOpenWindowSec = medianOpenWindowSec,
        flipsLast24h = flipsLast24h,
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
