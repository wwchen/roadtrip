package ca.floo.roadtrip.route.api.availability

import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.OptionalQuery
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.boundedIntQuery
import ca.floo.roadtrip.route.common.dateQueryValues
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.intQueryAtLeast
import ca.floo.roadtrip.route.common.longPath
import ca.floo.roadtrip.route.common.optionalBooleanQuery
import ca.floo.roadtrip.route.common.optionalDateQuery
import ca.floo.roadtrip.route.common.optionalLongQuery
import ca.floo.roadtrip.route.common.optionalOffsetDateTimeQuery
import ca.floo.roadtrip.route.common.queryParam
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.service.availability.AvailabilityDashboardController
import ca.floo.roadtrip.service.availability.AvailabilityDashboardResult
import ca.floo.roadtrip.service.availability.ForcePollerResult
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json

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

internal fun Route.availabilityDashboardRoutes(dashboard: AvailabilityDashboardController) {
    route("/api") {
        route("/availability") {
            route("/pollers") {
                get {
                    val active =
                        when (val activeQuery = call.optionalBooleanQuery("active")) {
                            OptionalQuery.Missing -> null
                            is OptionalQuery.Invalid ->
                                return@get call.respondError(
                                    "invalid_active",
                                    HttpStatusCode.BadRequest,
                                    "active must be true or false",
                                )
                            is OptionalQuery.Parsed -> activeQuery.value
                        }
                    val limit = call.boundedIntQuery("limit", DEFAULT_LIST_LIMIT, listLimitRange)
                    val offset = call.intQueryAtLeast("offset", DEFAULT_LIST_OFFSET, MIN_LIST_OFFSET)
                    call.respondJson(
                        dashboard.listPollers(active = active, limit = limit, offset = offset),
                    )
                }.describeApi("availability", "List availability pollers (coalesced per-vendor-call-unit schedulable)")
                    .access(RouteAccess.Anonymous)

                get("/summary") {
                    call.respondJson(
                        dashboard.pollersSummary(),
                    )
                }.describeApi("availability", "Poller counters for the dashboard header")
                    .access(RouteAccess.Anonymous)

                route("/{id}") {
                    get("/runs") {
                        val id =
                            call.longPath("id")
                                ?: return@get call.respondError("invalid_id", HttpStatusCode.BadRequest)
                        val limit = call.boundedIntQuery("limit", DEFAULT_LIST_LIMIT, listLimitRange)
                        call.respondJson(dashboard.listRunsForPoller(id, limit = limit))
                    }.describeApi("availability", "Runs for one poller, newest first")
                        .access(RouteAccess.Anonymous)

                    post("/force") {
                        val id =
                            call.longPath("id")
                                ?: return@post call.respondError("invalid_id", HttpStatusCode.BadRequest)
                        when (val result = dashboard.forcePoller(id)) {
                            is ForcePollerResult.Accepted -> call.respondJson(result.value)
                            is ForcePollerResult.Cooldown ->
                                call.respondJson(result.value, HttpStatusCode.TooManyRequests)
                            is ForcePollerResult.NotFound ->
                                call.respondError(result.error, HttpStatusCode.NotFound, result.detail)
                        }
                    }.describeApi("availability", "Force a poller due now ('check now'), rate-limited per poller")
                        .access(RouteAccess.Anonymous)
                }
            }

            route("/runs") {
                get {
                    val status = call.queryParam("status")
                    val pollerId = call.optionalLongQuery("poller_id")
                    val since = call.optionalOffsetDateTimeQuery("since")
                    val limit = call.boundedIntQuery("limit", DEFAULT_LIST_LIMIT, listLimitRange)
                    call.respondJson(dashboard.listRuns(status = status, pollerId = pollerId, since = since, limit = limit))
                }.describeApi("availability", "Recent runs across all pollers")
                    .access(RouteAccess.Anonymous)
            }

            route("/changes") {
                get {
                    val campsiteId = call.optionalLongQuery("campsite_id")
                    val poiId = call.optionalLongQuery("poi_id")
                    val targetDate = call.optionalDateQuery("target_date")
                    val limit = call.boundedIntQuery("limit", SNAPSHOT_DEFAULT_LIMIT, snapshotLimitRange)
                    when (
                        val result =
                            dashboard.listChanges(
                                campsiteId = campsiteId,
                                poiId = poiId,
                                targetDate = targetDate,
                                limit = limit,
                            )
                    ) {
                        is AvailabilityDashboardResult.Invalid ->
                            call.respondError(result.error, HttpStatusCode.BadRequest, result.detail)
                        is AvailabilityDashboardResult.NotFound ->
                            call.respondError(result.error, HttpStatusCode.NotFound, result.detail)
                        is AvailabilityDashboardResult.Ok ->
                            call.respondJson(result.value)
                    }
                }.describeApi("availability", "Availability change rows filtered by campsite_id or poi_id")
                    .access(RouteAccess.Anonymous)

                get("/summary") {
                    val poiId = call.optionalLongQuery("poi_id")
                    val explicitDates = call.dateQueryValues("dates")
                    when (
                        val result =
                            dashboard.changeSummary(
                                poiId = poiId,
                                explicitDates = explicitDates,
                            )
                    ) {
                        is AvailabilityDashboardResult.Invalid ->
                            call.respondError(result.error, HttpStatusCode.BadRequest, result.detail)
                        is AvailabilityDashboardResult.NotFound ->
                            call.respondError(result.error, HttpStatusCode.NotFound, result.detail)
                        is AvailabilityDashboardResult.Ok ->
                            call.respondJson(result.value)
                    }
                }.describeApi("availability", "Per-date stats aggregated across a POI's campsites")
                    .access(RouteAccess.Anonymous)
            }
        }
    }
}

private suspend inline fun <reified T> ApplicationCall.respondJson(
    body: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) = respondEncodedJson(dashboardJson, body, status)

private suspend fun ApplicationCall.respondError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) = respondApiError(error = error, status = status, detail = detail)
