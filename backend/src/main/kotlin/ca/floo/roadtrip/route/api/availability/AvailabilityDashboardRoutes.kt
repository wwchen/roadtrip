package ca.floo.roadtrip.route.api.availability

import ca.floo.roadtrip.model.api.ApiErrorSchema
import ca.floo.roadtrip.model.api.AvailabilityPollerSchema
import ca.floo.roadtrip.model.api.AvailabilityPollersListResponse
import ca.floo.roadtrip.model.api.AvailabilityPollersSummary
import ca.floo.roadtrip.model.api.AvailabilityRunSchema
import ca.floo.roadtrip.model.api.AvailabilityRunsListResponse
import ca.floo.roadtrip.model.api.AvailabilitySnapshotSchema
import ca.floo.roadtrip.model.api.AvailabilitySnapshotStatsSchema
import ca.floo.roadtrip.model.api.AvailabilitySnapshotsListResponse
import ca.floo.roadtrip.model.api.AvailabilitySnapshotsSummaryResponse
import ca.floo.roadtrip.model.api.CheckNowCooldownDto
import ca.floo.roadtrip.model.api.CheckNowResponseDto
import ca.floo.roadtrip.repo.AvailabilityPollerRepo
import ca.floo.roadtrip.repo.AvailabilityRepo
import ca.floo.roadtrip.repo.AvailabilityRunRepo
import ca.floo.roadtrip.repo.CampsiteRepo
import ca.floo.roadtrip.route.common.boundedIntQuery
import ca.floo.roadtrip.route.common.dateQueryValues
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.intQueryAtLeast
import ca.floo.roadtrip.route.common.longPath
import ca.floo.roadtrip.route.common.optionalLongQuery
import ca.floo.roadtrip.route.common.optionalOffsetDateTimeQuery
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jooq.DSLContext
import java.time.Duration
import java.time.OffsetDateTime

private const val DEFAULT_LIST_LIMIT = 100
private const val MIN_LIST_LIMIT = 1
private const val MAX_LIST_LIMIT = 500
private const val DEFAULT_LIST_OFFSET = 0
private const val MIN_LIST_OFFSET = 0
private const val SNAPSHOT_DEFAULT_LIMIT = 200
private const val SNAPSHOT_MAX_LIMIT = 1000
private const val SNAPSHOT_WINDOW_HOURS_MIN = 1
private const val SNAPSHOT_WINDOW_HOURS_MAX = 24 * 30
private const val SNAPSHOT_WINDOW_HOURS_DEFAULT = 24 * 7

private val listLimitRange = MIN_LIST_LIMIT..MAX_LIST_LIMIT
private val snapshotLimitRange = MIN_LIST_LIMIT..SNAPSHOT_MAX_LIMIT
private val snapshotWindowHoursRange = SNAPSHOT_WINDOW_HOURS_MIN..SNAPSHOT_WINDOW_HOURS_MAX

@OptIn(ExperimentalSerializationApi::class)
private val dashboardJson =
    Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

fun Route.availabilityDashboardRoutes(
    ctx: DSLContext,
    forcePullCooldown: Duration,
) {
    val pollers = AvailabilityPollerRepo(ctx)
    val runs = AvailabilityRunRepo(ctx)
    val availability = AvailabilityRepo(ctx)
    val campsitesRepo = CampsiteRepo(ctx)

    route("/api") {
        route("/availability") {
            route("/pollers") {
                get {
                    val active =
                        call.request.queryParameters["active"]?.let {
                            it.toBooleanStrictOrNull()
                                ?: return@get call.respondError("invalid_active", HttpStatusCode.BadRequest, "active must be true or false")
                        }
                    val limit = call.boundedIntQuery("limit", DEFAULT_LIST_LIMIT, listLimitRange)
                    val offset = call.intQueryAtLeast("offset", DEFAULT_LIST_OFFSET, MIN_LIST_OFFSET)
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
                }.describeApi("availability", "List availability pollers (coalesced per-vendor-call-unit schedulable)")

                get("/summary") {
                    val s = pollers.summary(OffsetDateTime.now())
                    call.respondJson(
                        AvailabilityPollersSummary(
                            active = s.active,
                            dormant = s.dormant,
                            dueNow = s.dueNow,
                            claimed = s.claimed,
                        ),
                    )
                }.describeApi("availability", "Poller counters for the dashboard header")

                route("/{id}") {
                    get("/runs") {
                        val id =
                            call.longPath("id")
                                ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
                        val limit = call.boundedIntQuery("limit", DEFAULT_LIST_LIMIT, listLimitRange)
                        val rows = runs.listForPoller(id, limit = limit)
                        call.respondJson(AvailabilityRunsListResponse(runs = rows.map { it.toSchema() }))
                    }.describeApi("availability", "Runs for one poller, newest first")

                    post("/force") {
                        val id =
                            call.longPath("id")
                                ?: return@post call.respondError("invalid_id", HttpStatusCode.BadRequest)
                        when (val result = pollers.forcePull(id, OffsetDateTime.now(), cooldown = forcePullCooldown)) {
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
                    }.describeApi("availability", "Force a poller due now ('check now'), rate-limited per poller")
                }
            }

            route("/runs") {
                get {
                    val status = call.request.queryParameters["status"]
                    val pollerId = call.optionalLongQuery("poller_id")
                    val since = call.optionalOffsetDateTimeQuery("since")
                    val limit = call.boundedIntQuery("limit", DEFAULT_LIST_LIMIT, listLimitRange)
                    val rows = runs.listSince(since = since, status = status, pollerId = pollerId, limit = limit)
                    call.respondJson(AvailabilityRunsListResponse(runs = rows.map { it.toSchema() }))
                }.describeApi("availability", "Recent runs across all pollers")
            }

            route("/snapshots") {
                get {
                    val campsiteId = call.optionalLongQuery("campsite_id")
                    val runId = call.optionalLongQuery("run_id")
                    if ((campsiteId == null) == (runId == null)) {
                        return@get call.respondError(
                            "invalid_filter",
                            HttpStatusCode.BadRequest,
                            "exactly one of campsite_id or run_id must be set",
                        )
                    }
                    val limit = call.boundedIntQuery("limit", SNAPSHOT_DEFAULT_LIMIT, snapshotLimitRange)
                    val rows =
                        if (campsiteId != null) {
                            campsitesRepo.findById(campsiteId)
                                ?: return@get call.respondError(
                                    "campsite_not_found",
                                    HttpStatusCode.NotFound,
                                    "no campsite with id $campsiteId",
                                )
                            availability.listForCampsite(campsiteId, limit = limit)
                        } else {
                            availability.listForRun(runId!!, limit = limit)
                        }
                    call.respondJson(AvailabilitySnapshotsListResponse(snapshots = rows.map { it.toSchema() }))
                }.describeApi("availability", "Snapshot rows filtered by campsite id or run id")

                get("/summary") {
                    val campsiteId =
                        call.optionalLongQuery("campsite_id")
                            ?: return@get call.respondError(
                                "missing_campsite_id",
                                HttpStatusCode.BadRequest,
                                "campsite_id is required",
                            )
                    campsitesRepo.findById(campsiteId)
                        ?: return@get call.respondError(
                            "campsite_not_found",
                            HttpStatusCode.NotFound,
                            "no campsite with id $campsiteId",
                        )
                    val windowHours =
                        call.boundedIntQuery(
                            "window_hours",
                            SNAPSHOT_WINDOW_HOURS_DEFAULT,
                            snapshotWindowHoursRange,
                        )
                    val explicitDates = call.dateQueryValues("dates")
                    val dates =
                        explicitDates.ifEmpty {
                            availability.datesWithSnapshotsInWindow(
                                campsiteId = campsiteId,
                                windowStart = OffsetDateTime.now().minusHours(windowHours.toLong()),
                            )
                        }
                    val stats = availability.summarize(campsiteId, dates, windowHours = windowHours)
                    call.respondJson(
                        AvailabilitySnapshotsSummaryResponse(
                            campsiteId = campsiteId,
                            stats = stats.map { it.toSchema() },
                        ),
                    )
                }.describeApi("availability", "Per-date stats for one campsite's snapshot history")
            }
        }
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

private fun AvailabilityRepo.StatusRun.toSchema(): AvailabilitySnapshotSchema =
    AvailabilitySnapshotSchema(
        campsiteId = campsiteId,
        runId = runId,
        targetDate = targetDate.toString(),
        observedFrom = observedFrom?.toString(),
        observedAt = lastObservedAt.toString(),
        status = status,
        available = available,
    )

private fun AvailabilityRepo.TargetDateStats.toSchema(): AvailabilitySnapshotStatsSchema =
    AvailabilitySnapshotStatsSchema(
        targetDate = targetDate.toString(),
        totalRuns = totalRuns,
        lastOpenAt = lastOpenAt?.toString(),
        isCurrentlyOpen = isCurrentlyOpen,
        currentOrLastOpenWindowSec = currentOrLastOpenWindowSec,
        medianOpenWindowSec = medianOpenWindowSec,
        opensLast24h = opensLast24h,
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
