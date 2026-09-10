package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.model.api.BulkAvailabilityRequestDto
import ca.floo.roadtrip.model.domain.CampsiteKind
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.BAD_REQUEST_ERROR
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.SiteTypeQuery
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.mapCatching
import ca.floo.roadtrip.route.common.parseSiteTypes
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.route.common.respondEncodedJson
import ca.floo.roadtrip.route.common.siteTypeWireList
import ca.floo.roadtrip.route.common.unknownSiteTypeDetail
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.availability.BulkAvailabilityController
import ca.floo.roadtrip.service.availability.BulkAvailabilityRequest
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.LocalDate

/**
 * Every wire code [validated] can throw via `require`/`error`. A
 * [RouteBodyResult.Invalid] whose detail isn't one of these came from
 * `receiveJsonBody` itself (malformed JSON, a wrong-typed field) and carries
 * raw `kotlinx.serialization` exception text — never safe to put on the wire.
 */
private val bulkValidationErrorCodes =
    setOf(BAD_REQUEST_ERROR, "too_many_pois", "bad_min_nights", "bad_date_window", "end_before_start")

internal fun Route.bulkAvailabilityRoutes(
    controller: BulkAvailabilityController,
    config: BulkAvailabilityConfig,
    rateLimit: IpRateLimiter = IpRateLimiter(perMinute = config.ipRateLimitPerMinute),
) {
    route("/api") {
        route("/pois") {
            post("/availability/bulk") {
                if (!rateLimit.allow(call.request.origin.remoteHost)) {
                    call.respondBulkError("ip_throttled", HttpStatusCode.ServiceUnavailable)
                    return@post
                }

                val dto =
                    when (val body = call.receiveJsonBody<BulkAvailabilityRequestDto>()) {
                        is RouteBodyResult.Invalid -> {
                            call.respondBulkError(BAD_REQUEST_ERROR, HttpStatusCode.BadRequest)
                            return@post
                        }
                        is RouteBodyResult.Valid -> body.value
                    }

                val siteTypes =
                    when (val parsed = parseSiteTypes(dto.siteTypes)) {
                        is SiteTypeQuery.Unknown ->
                            return@post call.respondBulkError(
                                BAD_REQUEST_ERROR,
                                HttpStatusCode.BadRequest,
                                detail = unknownSiteTypeDetail(parsed.value),
                            )
                        is SiteTypeQuery.Parsed -> parsed.kinds
                    }

                val request =
                    when (
                        val validated =
                            RouteBodyResult.Valid(dto).mapCatching { it.validated(config, siteTypes) }
                    ) {
                        is RouteBodyResult.Invalid -> {
                            val code = validated.detail?.takeIf { it in bulkValidationErrorCodes } ?: BAD_REQUEST_ERROR
                            call.respondBulkError(code, HttpStatusCode.BadRequest)
                            return@post
                        }
                        is RouteBodyResult.Valid -> validated.value
                    }

                call.respondEncodedJson(
                    controller.availabilityForPois(request),
                    HttpStatusCode.OK,
                )
            }.describeApi(
                tag = "availability",
                summary = "Per-campsite availability across many campground POIs",
                description =
                    "Body: { poi_ids: [pois.id, ...1..${config.maxPois}], start_date, end_date, " +
                        "min_nights?, site_type? }. Returns one entry per requested POI, in request " +
                        "order. Each entry carries either its campsites — one envelope per campsite, " +
                        "each day a single cell — filtered to `longest_run_nights >= min_nights` and " +
                        "sorted descending, or an error code. A POI failing never fails the request. " +
                        "`site_type` accepts: $siteTypeWireList.",
            ).access(RouteAccess.Anonymous)
        }
    }
}

private fun BulkAvailabilityRequestDto.validated(
    config: BulkAvailabilityConfig,
    siteTypes: List<CampsiteKind>,
): BulkAvailabilityRequest {
    require(poiIds.isNotEmpty()) { BAD_REQUEST_ERROR }
    require(poiIds.size <= config.maxPois) { "too_many_pois" }
    require(minNights >= 1) { "bad_min_nights" }
    // Both dates are required: with either left null each POI would resolve its own
    // default window from its own centroid timezone, so run lengths would no longer
    // be comparable across POIs — defeating the endpoint's purpose (see spec Decision 5).
    require(!startDate.isNullOrBlank()) { "bad_date_window" }
    require(!endDate.isNullOrBlank()) { "bad_date_window" }
    val start = parseDate(startDate)
    val end = parseDate(endDate)
    // Checked here as well as per POI: an inverted window is wrong for every POI,
    // so rejecting it up front avoids a whole fan-out that can only fail.
    require(start == null || end == null || end.isAfter(start)) { "end_before_start" }
    return BulkAvailabilityRequest(
        poiIds = poiIds,
        startDate = start,
        endDate = end,
        minNights = minNights,
        siteTypes = siteTypes,
    )
}

private fun parseDate(raw: String?): LocalDate? =
    raw?.takeIf { it.isNotBlank() }?.let {
        try {
            LocalDate.parse(it)
        } catch (e: Exception) {
            error("bad_date_window")
        }
    }

private suspend fun ApplicationCall.respondBulkError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondEncodedJson(availabilityErrorDto(error, detail = detail), status)
}
