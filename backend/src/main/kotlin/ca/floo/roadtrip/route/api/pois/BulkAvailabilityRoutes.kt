package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.model.api.BulkAvailabilityRequestDto
import ca.floo.roadtrip.model.api.StayAnswerDto
import ca.floo.roadtrip.model.api.StayCheckDoneDto
import ca.floo.roadtrip.model.api.StayCheckRequestDto
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
import ca.floo.roadtrip.route.common.respondEventStream
import ca.floo.roadtrip.route.common.siteTypeWireList
import ca.floo.roadtrip.route.common.unknownSiteTypeDetail
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.availability.BulkAvailabilityController
import ca.floo.roadtrip.service.availability.BulkAvailabilityRequest
import ca.floo.roadtrip.service.availability.STAY_TIMEOUT_ERROR
import ca.floo.roadtrip.service.availability.StayCheckRequest
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
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
    setOf(BAD_REQUEST_ERROR, "too_many_pois", "bad_min_nights", "bad_group_size", "bad_date_window", "end_before_start")

internal fun Route.bulkAvailabilityRoutes(
    controller: BulkAvailabilityController,
    config: BulkAvailabilityConfig,
    rateLimit: IpRateLimiter = IpRateLimiter(perMinute = config.ipRateLimitPerMinute),
) {
    route("/api") {
        route("/pois") {
            post("/availability/bulk") {
                if (throttled(rateLimit)) return@post
                val dto = call.receiveJsonBody<BulkAvailabilityRequestDto>().orRefuse(call) ?: return@post
                val siteTypes = call.siteTypesOrRefuse(dto.siteTypes) ?: return@post
                val request = validatedOrRefuse(call) { dto.validated(config, siteTypes) } ?: return@post

                call.respondEncodedJson(controller.availabilityForPois(request), HttpStatusCode.OK)
            }.describeApi(
                tag = "availability",
                summary = "Per-campsite availability across many campground POIs",
                description =
                    "Body: { poi_ids: [pois.id, ...1..${config.maxPois}], start_date, end_date, " +
                        "min_nights?, site_type?, group_size?, fresh? }. Returns one entry per requested " +
                        "POI, in request order, each carrying its campsites — one envelope per campsite, " +
                        "each day a single cell — filtered to `longest_run_nights >= min_nights` and " +
                        "sorted descending, or an error code: a POI failing never fails the request. " +
                        "`group_size` keeps campsites that sleep at least that many, and those with no " +
                        "stated capacity. `fresh: true` answers only from observations at most " +
                        "${config.freshTolerance} old, asking the providers again for the rest. " +
                        "`site_type` accepts: $siteTypeWireList.",
            ).access(RouteAccess.Anonymous)

            post("/availability/bulk/stream") {
                if (throttled(rateLimit)) return@post
                val dto = call.receiveJsonBody<StayCheckRequestDto>().orRefuse(call) ?: return@post
                val siteTypes = call.siteTypesOrRefuse(dto.siteTypes) ?: return@post
                val request = validatedOrRefuse(call) { dto.validated(config, siteTypes) } ?: return@post

                call.respondEventStream(config.streamHeartbeat) {
                    var answered = 0
                    controller.stayAnswers(request).collect { answer ->
                        send(StayAnswerDto.EVENT, StayAnswerDto.serializer(), answer)
                        answered += 1
                    }
                    send(StayCheckDoneDto.EVENT, StayCheckDoneDto.serializer(), StayCheckDoneDto(answered = answered))
                }
            }.describeApi(
                tag = "availability",
                summary = "How one stay looks at many campground POIs, streamed as each answers",
                description =
                    "Body: { poi_ids: [pois.id, ...1..${config.maxPois}], start_date, end_date, " +
                        "site_type?, group_size?, fresh? }. Answers 200 as a text/event-stream: one " +
                        "`${StayAnswerDto.EVENT}` event per requested POI as it lands — POIs start in " +
                        "request order, so send the nearest first — carrying how the stay looks there " +
                        "(`open`, `few_left`, `full`, `closed_for_season` or `no_data`, and how many sites " +
                        "are open every night from start_date up to end_date) or an error code, then one " +
                        "`${StayCheckDoneDto.EVENT}` event, and the stream closes. A POI that takes longer " +
                        "than ${config.perPoiTimeout} answers `$STAY_TIMEOUT_ERROR`; a quiet stream sends a " +
                        "keep-alive comment every ${config.streamHeartbeat}. A refused request answers " +
                        "JSON with the status, as before any event is sent. `site_type` accepts: " +
                        "$siteTypeWireList.",
            ).access(RouteAccess.Anonymous)
        }
    }
}

/** Refuses with 503 when the caller is over the endpoints' per-caller limit; true when it did. */
private suspend fun RoutingContext.throttled(rateLimit: IpRateLimiter): Boolean {
    if (rateLimit.allow(call.request.origin.remoteHost)) return false
    call.respondBulkError("ip_throttled", HttpStatusCode.ServiceUnavailable)
    return true
}

/** The decoded body, or null after refusing a malformed one — whose parser text is never put on the wire. */
private suspend fun <T> RouteBodyResult<T>.orRefuse(call: ApplicationCall): T? =
    when (this) {
        is RouteBodyResult.Invalid -> {
            call.respondBulkError(BAD_REQUEST_ERROR, HttpStatusCode.BadRequest)
            null
        }
        is RouteBodyResult.Valid -> value
    }

/** The `site_type` values as kinds, or null after refusing one outside the vocabulary. */
private suspend fun ApplicationCall.siteTypesOrRefuse(raw: List<String>): List<CampsiteKind>? =
    when (val parsed = parseSiteTypes(raw)) {
        is SiteTypeQuery.Unknown -> {
            respondBulkError(BAD_REQUEST_ERROR, HttpStatusCode.BadRequest, detail = unknownSiteTypeDetail(parsed.value))
            null
        }
        is SiteTypeQuery.Parsed -> parsed.kinds
    }

/** [validate]'s request, or null after refusing with the wire code it threw. */
private suspend fun <T> validatedOrRefuse(
    call: ApplicationCall,
    validate: () -> T,
): T? =
    when (val validated = RouteBodyResult.Valid(Unit).mapCatching { validate() }) {
        is RouteBodyResult.Invalid -> {
            val code = validated.detail?.takeIf { it in bulkValidationErrorCodes } ?: BAD_REQUEST_ERROR
            call.respondBulkError(code, HttpStatusCode.BadRequest)
            null
        }
        is RouteBodyResult.Valid -> validated.value
    }

private fun BulkAvailabilityRequestDto.validated(
    config: BulkAvailabilityConfig,
    siteTypes: List<CampsiteKind>,
): BulkAvailabilityRequest {
    requirePoiIds(poiIds, config)
    require(minNights >= 1) { "bad_min_nights" }
    requireGroupSize(groupSize)
    val (start, end) = requiredWindow(startDate, endDate)
    return BulkAvailabilityRequest(
        poiIds = poiIds,
        startDate = start,
        endDate = end,
        minNights = minNights,
        siteTypes = siteTypes,
        groupSize = groupSize,
        fresh = fresh,
    )
}

private fun StayCheckRequestDto.validated(
    config: BulkAvailabilityConfig,
    siteTypes: List<CampsiteKind>,
): StayCheckRequest {
    requirePoiIds(poiIds, config)
    requireGroupSize(groupSize)
    val (start, end) = requiredWindow(startDate, endDate)
    return StayCheckRequest(
        poiIds = poiIds,
        startDate = start,
        endDate = end,
        siteTypes = siteTypes,
        groupSize = groupSize,
        fresh = fresh,
    )
}

private fun requirePoiIds(
    poiIds: List<Long>,
    config: BulkAvailabilityConfig,
) {
    require(poiIds.isNotEmpty()) { BAD_REQUEST_ERROR }
    require(poiIds.size <= config.maxPois) { "too_many_pois" }
}

private fun requireGroupSize(groupSize: Int?) {
    require(groupSize == null || groupSize >= 1) { "bad_group_size" }
}

/**
 * Both dates, parsed, the end after the start. Both are required: with either
 * left null each POI would resolve its own default window from its own centroid
 * timezone, so answers would no longer be comparable across POIs — defeating
 * the endpoints' purpose (see spec Decision 5). The order is checked here as
 * well as per POI: an inverted window is wrong for every POI, so refusing it up
 * front avoids a whole fan-out that can only fail.
 */
private fun requiredWindow(
    startDate: String?,
    endDate: String?,
): Pair<LocalDate, LocalDate> {
    val start = parseDate(startDate) ?: error("bad_date_window")
    val end = parseDate(endDate) ?: error("bad_date_window")
    require(end.isAfter(start)) { "end_before_start" }
    return start to end
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
