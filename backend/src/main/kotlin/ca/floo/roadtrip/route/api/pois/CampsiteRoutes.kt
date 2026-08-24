package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.model.api.AvailabilityErrorDto
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import ca.floo.roadtrip.route.common.longPath
import ca.floo.roadtrip.route.common.optionalDateQuery
import ca.floo.roadtrip.route.common.queryValues
import ca.floo.roadtrip.route.common.respondApiError
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.service.availability.AvailabilityServiceError
import ca.floo.roadtrip.service.availability.CampsiteAvailabilityController
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import ca.floo.roadtrip.support.UpstreamHttpException
import ca.floo.roadtrip.support.causeChain
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("CampsiteRoutes")

private const val IP_RATE_LIMIT_PER_MINUTE = 30

internal fun Route.campsiteRoutes(
    controller: CampsiteAvailabilityController,
    rateLimit: IpRateLimiter = IpRateLimiter(perMinute = IP_RATE_LIMIT_PER_MINUTE),
) {
    route("/api") {
        route("/pois") {
            route("/{id}") {
                route("/campsites") {
                    get {
                        val poiId =
                            call.longPath("id")
                                ?: return@get call.respondCampsiteError("bad_id", HttpStatusCode.BadRequest)
                        try {
                            call.respondCampsiteJson(
                                controller.campsitesForPoi(
                                    poiId = poiId,
                                    siteTypes = call.queryValues("site_type", "siteType"),
                                ),
                            )
                        } catch (e: AvailabilityServiceError.NotFound) {
                            call.respondCampsiteError(e.error, HttpStatusCode.NotFound)
                        }
                    }.describeApi(
                        tag = "campsite",
                        summary = "Campsites linked to a campground POI",
                        description =
                            "Lists active campsite rows linked to a campground POI. " +
                                "`site_type` optionally filters exact campsite kinds.",
                    ).access(RouteAccess.Anonymous)

                    get("/availability") {
                        val poiId =
                            call.longPath("id")
                                ?: return@get call.respondAvailabilityError("bad_poi_id", HttpStatusCode.BadRequest)

                        if (!rateLimit.allow(call.request.origin.remoteHost)) {
                            call.respondAvailabilityError("ip_throttled", HttpStatusCode.ServiceUnavailable)
                            return@get
                        }

                        val startDate =
                            try {
                                call.optionalDateQuery("start_date")
                            } catch (e: Exception) {
                                call.respondAvailabilityError("bad_date_window", HttpStatusCode.BadRequest)
                                return@get
                            }
                        val endDate =
                            try {
                                call.optionalDateQuery("end_date")
                            } catch (e: Exception) {
                                call.respondAvailabilityError("bad_date_window", HttpStatusCode.BadRequest)
                                return@get
                            }

                        try {
                            call.respondAvailabilityJson(
                                controller.availabilityForPoi(
                                    poiId = poiId,
                                    siteTypes = call.queryValues("site_type", "siteType"),
                                    startDate = startDate,
                                    endDate = endDate,
                                ),
                            )
                        } catch (e: AvailabilityServiceError) {
                            call.respondServiceAvailabilityError(e)
                        } catch (e: AvailabilityProviderError) {
                            val (status, error) = mapProviderError(e)
                            log.error(
                                "poi campsites availability poi={} failed: {} upstreamStatus={} cause={}",
                                poiId,
                                error.error,
                                error.upstreamStatus,
                                causeChain(e),
                                e,
                            )
                            call.respondAvailabilityJson(error, status)
                        }
                    }.describeApi(
                        tag = "availability",
                        summary = "Per-campsite availability for one campground POI",
                        description =
                            "Path key is `pois.id`. Returns one availability envelope per " +
                                "campsite linked to this POI. The frontend fuses the per-campsite streams " +
                                "into the campground week grid.",
                    ).access(RouteAccess.Anonymous)
                }
            }
        }
    }
}

internal fun mapProviderError(e: AvailabilityProviderError): Pair<HttpStatusCode, AvailabilityErrorDto> {
    val upstream = upstreamHttpStatus(e)
    val status =
        when (e) {
            is AvailabilityProviderError.RateLimited,
            is AvailabilityProviderError.UpstreamBlocked,
            is AvailabilityProviderError.UpstreamUnavailable,
            is AvailabilityProviderError.UpstreamUnreachable,
            is AvailabilityProviderError.Unknown,
            -> HttpStatusCode.ServiceUnavailable
            is AvailabilityProviderError.Unsupported -> HttpStatusCode.NotImplemented
            is AvailabilityProviderError.Misconfigured,
            is AvailabilityProviderError.WrongRefType,
            -> HttpStatusCode.InternalServerError
        }
    val upstreamStatus = if (status == HttpStatusCode.ServiceUnavailable) upstream else null
    return status to availabilityErrorDto(e.code, upstreamStatus = upstreamStatus)
}

/**
 * Extracts the upstream HTTP status a request failed on, if any vendor wrapper
 * in the cause chain carries one. Matches [UpstreamHttpException] rather than a
 * single vendor, so Campflare/ReserveAmerica/ReserveCalifornia 5xx surface
 * `upstream_status` the same way Aspira does. Walks the chain under the same
 * depth/cycle guard as [causeChain] so a self-referential cause can't spin.
 */
internal fun upstreamHttpStatus(e: AvailabilityProviderError): Int? {
    var t: Throwable? = e.cause
    var depth = 0
    val seen = mutableSetOf<Throwable>()
    while (t != null && depth++ < MAX_CAUSE_DEPTH && seen.add(t)) {
        val status = (t as? UpstreamHttpException)?.httpStatus
        if (status != null) return status
        t = t.cause
    }
    return null
}

/** Depth cap so a self-referential cause chain can't spin or flood a log line. */
private const val MAX_CAUSE_DEPTH = 8

private suspend fun ApplicationCall.respondCampsiteError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondApiError(error = error, status = status, detail = detail)
}

private suspend inline fun <reified T> ApplicationCall.respondCampsiteJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(encodeAvailabilityJson(value), ContentType.Application.Json, status)
}

private suspend fun ApplicationCall.respondAvailabilityError(
    error: String,
    status: HttpStatusCode,
) {
    respondAvailabilityJson(availabilityErrorDto(error), status)
}

private suspend fun ApplicationCall.respondServiceAvailabilityError(e: AvailabilityServiceError) {
    val status =
        when (e) {
            is AvailabilityServiceError.BadDateWindow -> HttpStatusCode.BadRequest
            AvailabilityServiceError.NotFound -> HttpStatusCode.NotFound
            AvailabilityServiceError.UnknownCampground -> HttpStatusCode.NotFound
        }
    val body =
        when (e) {
            is AvailabilityServiceError.BadDateWindow -> availabilityErrorDto(e)
            else -> availabilityErrorDto(e.error)
        }
    respondAvailabilityJson(body, status)
}

private suspend inline fun <reified T> ApplicationCall.respondAvailabilityJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(encodeAvailabilityJson(value), ContentType.Application.Json, status)
}

private fun availabilityErrorDto(e: AvailabilityServiceError.BadDateWindow): AvailabilityErrorDto =
    when (e) {
        is AvailabilityServiceError.BadDateWindow.StartBeforeEarliest ->
            availabilityErrorDto(
                error = e.error,
                earliestDate = e.earliestDate.toString(),
                timeZone = e.timeZone.id,
            )
        AvailabilityServiceError.BadDateWindow.EndBeforeStart ->
            availabilityErrorDto(error = e.error)
        is AvailabilityServiceError.BadDateWindow.WindowTooLong ->
            availabilityErrorDto(error = e.error, maxDays = e.maxDays)
        is AvailabilityServiceError.BadDateWindow.BeyondBookingHorizon ->
            availabilityErrorDto(error = e.error, latestDate = e.latestDate.toString())
        AvailabilityServiceError.BadDateWindow.Invalid ->
            availabilityErrorDto(error = e.error)
    }
