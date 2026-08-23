package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.model.api.BulkAvailabilityRequestDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.RouteBodyResult
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.mapCatching
import ca.floo.roadtrip.route.common.receiveJsonBody
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.service.availability.BulkAvailabilityController
import ca.floo.roadtrip.service.availability.BulkAvailabilityRequest
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
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
private val bulkValidationErrorCodes = setOf("bad_request", "too_many_pois", "bad_min_nights", "bad_date_window")

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

                val request =
                    when (
                        val body =
                            call
                                .receiveJsonBody<BulkAvailabilityRequestDto>()
                                .mapCatching { it.validated(config) }
                    ) {
                        is RouteBodyResult.Invalid -> {
                            val code = body.detail?.takeIf { it in bulkValidationErrorCodes } ?: "bad_request"
                            call.respondBulkError(code, HttpStatusCode.BadRequest)
                            return@post
                        }
                        is RouteBodyResult.Valid -> body.value
                    }

                call.respondText(
                    encodeAvailabilityJson(controller.availabilityForPois(request)),
                    ContentType.Application.Json,
                    HttpStatusCode.OK,
                )
            }.describeApi(
                tag = "availability",
                summary = "Per-campsite availability across many campground POIs",
                description =
                    "Body: { poi_ids: [pois.id, ...1..${config.maxPois}], start_date, end_date, " +
                        "min_nights?, site_type? }. Returns one entry per requested POI, in request " +
                        "order. Each entry carries either its campsites — filtered to " +
                        "`longest_run_nights >= min_nights` and sorted descending — or an error code. " +
                        "A POI failing never fails the request.",
            ).access(RouteAccess.Anonymous)
        }
    }
}

private fun BulkAvailabilityRequestDto.validated(config: BulkAvailabilityConfig): BulkAvailabilityRequest {
    require(poiIds.isNotEmpty()) { "bad_request" }
    require(poiIds.size <= config.maxPois) { "too_many_pois" }
    require(minNights >= 1) { "bad_min_nights" }
    // Both dates are required: with either left null each POI would resolve its own
    // default window from its own centroid timezone, so run lengths would no longer
    // be comparable across POIs — defeating the endpoint's purpose (see spec Decision 5).
    require(!startDate.isNullOrBlank()) { "bad_date_window" }
    require(!endDate.isNullOrBlank()) { "bad_date_window" }
    return BulkAvailabilityRequest(
        poiIds = poiIds,
        startDate = parseDate(startDate),
        endDate = parseDate(endDate),
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
) {
    respondText(encodeAvailabilityJson(availabilityErrorDto(error)), ContentType.Application.Json, status)
}
