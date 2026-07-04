package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityPollerSchema
import ca.floo.roadtrip.models.api.AvailabilityPollersListResponse
import ca.floo.roadtrip.models.api.AvailabilityPollersSummary
import ca.floo.roadtrip.models.api.AvailabilityRunSchema
import ca.floo.roadtrip.models.api.AvailabilityRunsListResponse
import ca.floo.roadtrip.models.api.AvailabilitySnapshotSchema
import ca.floo.roadtrip.models.api.AvailabilitySnapshotStatsSchema
import ca.floo.roadtrip.models.api.AvailabilitySnapshotsListResponse
import ca.floo.roadtrip.models.api.AvailabilitySnapshotsSummaryResponse
import ca.floo.roadtrip.models.api.CheckNowCooldownDto
import ca.floo.roadtrip.models.api.CheckNowResponseDto
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
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

/**
 * Per-poller "check now" cooldown: the minimum spacing between two human-forced
 * pulls of the same poller. Keeps a user mashing the button from starving the
 * shared vendor governor (PR4) for everyone attached to this poller. Overridable
 * via the `FORCE_PULL_COOLDOWN_SEC` env var.
 */
private val FORCE_PULL_COOLDOWN: java.time.Duration =
    java.time.Duration.ofSeconds(
        System.getenv("FORCE_PULL_COOLDOWN_SEC")?.toLongOrNull() ?: 60L,
    )

@OptIn(ExperimentalSerializationApi::class)
private val dashboardJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

fun Route.availabilityDashboardRoutes(ctx: DSLContext) {
    val pollers = AvailabilityPollerRepo(ctx)
    val runs = AvailabilityRunRepo(ctx)
    val snapshots = AvailabilitySnapshotRepo(ctx)
    val reservablesRepo =
        ca.floo.roadtrip.repo
            .ReservableRepo(ctx)

    get("/api/availability/pollers", {
        tags = listOf("availability")
        summary = "List availability pollers (coalesced per-vendor-call-unit schedulable)"
        request {
            queryParameter<String>("active") { description = "true | false; omit for both." }
            queryParameter<Int>("limit") { description = "Page size, default 100, max 500." }
            queryParameter<Int>("offset") { description = "Page offset, default 0." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityPollersListResponse> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val active =
            call.request.queryParameters["active"]?.let {
                it.toBooleanStrictOrNull()
                    ?: return@get call.respondError("invalid_active", HttpStatusCode.BadRequest, "active must be true or false")
            }
        val limit =
            (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIST_LIMIT)
                .coerceIn(1, MAX_LIST_LIMIT)
        val offset =
            call.request.queryParameters["offset"]
                ?.toIntOrNull()
                ?.coerceAtLeast(0) ?: 0
        val rows = pollers.list(active = active, limit = limit, offset = offset)
        val total = pollers.count(active = active)
        call.respondJson(
            AvailabilityPollersListResponse(
                total = total,
                limit = limit,
                offset = offset,
                pollers = rows.map { it.toSchema() },
            ),
        )
    }

    get("/api/availability/pollers/summary", {
        tags = listOf("availability")
        summary = "Poller counters for the dashboard header"
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityPollersSummary> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val s = pollers.summary(OffsetDateTime.now())
        call.respondJson(
            AvailabilityPollersSummary(
                active = s.active,
                dormant = s.dormant,
                dueNow = s.dueNow,
                claimed = s.claimed,
            ),
        )
    }

    get("/api/availability/pollers/{id}/runs", {
        tags = listOf("availability")
        summary = "Runs for one poller, newest first"
        request {
            pathParameter<Long>("id") { description = "Poller id." }
            queryParameter<Int>("limit") { description = "Page size, default 100, max 500." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityRunsListResponse> { mediaTypes(ContentType.Application.Json) }
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
        val rows = runs.listForPoller(id, limit = limit)
        call.respondJson(AvailabilityRunsListResponse(runs = rows.map { it.toSchema() }))
    }

    post("/api/availability/pollers/{id}/force", {
        tags = listOf("availability")
        summary = "Force a poller due now ('check now'), rate-limited per poller"
        request {
            pathParameter<Long>("id") { description = "Poller id." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<CheckNowResponseDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.NotFound) { body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) } }
            code(HttpStatusCode.TooManyRequests) { body<CheckNowCooldownDto> { mediaTypes(ContentType.Application.Json) } }
        }
    }) {
        val id =
            call.parameters["id"]?.toLongOrNull()
                ?: return@post call.respondError("invalid_id", HttpStatusCode.BadRequest)
        when (val result = pollers.forcePull(id, OffsetDateTime.now(), cooldown = FORCE_PULL_COOLDOWN)) {
            is AvailabilityPollerRepo.ForcePullResult.Accepted ->
                call.respondJson(
                    CheckNowResponseDto(pollerId = id, nextRunAt = result.nextRunAt.toString()),
                )

            is AvailabilityPollerRepo.ForcePullResult.Cooldown ->
                call.respondJson(
                    CheckNowCooldownDto(pollerId = id, retryAfterSec = result.retryAfterSec),
                    HttpStatusCode.TooManyRequests,
                )

            AvailabilityPollerRepo.ForcePullResult.NotFound ->
                call.respondError("poller_not_found", HttpStatusCode.NotFound, "no poller with id $id")
        }
    }

    get("/api/availability/runs", {
        tags = listOf("availability")
        summary = "Recent runs across all pollers"
        request {
            queryParameter<String>("status") { description = "started | completed | failed" }
            queryParameter<Long>("poller_id") { description = "Scope to one poller." }
            queryParameter<String>("since") { description = "ISO-8601 timestamp; only runs after this." }
            queryParameter<Int>("limit") { description = "Page size, default 100, max 500." }
        }
        response {
            code(HttpStatusCode.OK) {
                body<AvailabilityRunsListResponse> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val status = call.request.queryParameters["status"]
        val pollerId = call.request.queryParameters["poller_id"]?.toLongOrNull()
        val since =
            call.request.queryParameters["since"]?.let {
                runCatching { OffsetDateTime.parse(it) }.getOrNull()
            }
        val limit =
            (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIST_LIMIT)
                .coerceIn(1, MAX_LIST_LIMIT)
        val rows = runs.listSince(since = since, status = status, pollerId = pollerId, limit = limit)
        call.respondJson(AvailabilityRunsListResponse(runs = rows.map { it.toSchema() }))
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
                    ca.floo.roadtrip.models.domain.ReservableId
                        .parse(rid)
                        ?: return@get call.respondError(
                            "invalid_reservable_rid",
                            HttpStatusCode.BadRequest,
                            "could not parse reservable_rid '$rid'",
                        )
                val reservable =
                    reservablesRepo.findByRid(parsed)
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
            ca.floo.roadtrip.models.domain.ReservableId
                .parse(rid)
                ?: return@get call.respondError(
                    "invalid_reservable_rid",
                    HttpStatusCode.BadRequest,
                    "could not parse reservable_rid '$rid'",
                )
        val reservable =
            reservablesRepo.findByRid(parsed)
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
                val snap = ca.floo.roadtrip.db.generated.tables.AvailabilitySnapshot.AVAILABILITY_SNAPSHOT
                val cell = ca.floo.roadtrip.db.generated.tables.AvailabilityCell.AVAILABILITY_CELL
                val windowStart =
                    java.time.OffsetDateTime
                        .now()
                        .minusHours(windowHours.toLong())
                // Dates that saw a snapshot edge in the window, UNIONed with the
                // current/future dates tracked in the cube. Snapshots are edge-only,
                // so a stable cell whose last edge predates the window has no rows in
                // it; the cube keeps such dates from dropping out of the summary.
                val snapDates =
                    ctx
                        .selectDistinct(snap.TARGET_DATE)
                        .from(snap)
                        .where(snap.RESERVABLE_ID.eq(reservable.id))
                        .and(snap.OBSERVED_AT.ge(windowStart))
                        .fetch { it.value1() }
                val cubeDates =
                    ctx
                        .selectDistinct(cell.TARGET_DATE)
                        .from(cell)
                        .where(cell.RESERVABLE_ID.eq(reservable.id))
                        .and(cell.TARGET_DATE.ge(java.time.LocalDate.now()))
                        .fetch { it.value1() }
                (snapDates + cubeDates).distinct().sorted()
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

private fun AvailabilityPollerRepo.PollerListItem.toSchema(): AvailabilityPollerSchema =
    AvailabilityPollerSchema(
        id = poller.id,
        provider = poller.provider,
        parentRef = poller.parentRef,
        poiId = poller.poiId,
        active = poller.active,
        nextRunAt = poller.nextRunAt.toString(),
        claimedUntil = poller.claimedUntil?.toString(),
        lastRunAt = poller.lastRunAt?.toString(),
        attachedWatches = attachedWatches,
        createdAt = poller.createdAt.toString(),
        updatedAt = poller.updatedAt.toString(),
    )

private fun AvailabilityRunRepo.Run.toSchema(): AvailabilityRunSchema =
    AvailabilityRunSchema(
        id = id,
        pollerId = pollerId,
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
