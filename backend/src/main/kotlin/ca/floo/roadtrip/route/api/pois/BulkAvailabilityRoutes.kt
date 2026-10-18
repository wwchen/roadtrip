package ca.floo.roadtrip.route.api.pois

import ca.floo.roadtrip.config.BulkAvailabilityConfig
import ca.floo.roadtrip.model.api.StayAnswerDto
import ca.floo.roadtrip.model.api.StayAvailabilityStatus
import ca.floo.roadtrip.model.api.StayCheckDoneDto
import ca.floo.roadtrip.model.api.StayCheckOutcome
import ca.floo.roadtrip.model.api.StayCheckRequestDto
import ca.floo.roadtrip.model.api.StayOutcome
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
import ca.floo.roadtrip.route.common.wireName
import ca.floo.roadtrip.route.common.wireNameList
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.availability.BulkAvailabilityController
import ca.floo.roadtrip.service.availability.StayCheckRequest
import ca.floo.roadtrip.service.availability.StayStreamItem
import ca.floo.roadtrip.service.ratelimit.IpRateLimiter
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.origin
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Duration
import java.time.LocalDate

/**
 * Every wire code [validated] can throw via `require`/`error`. A
 * [RouteBodyResult.Invalid] whose detail isn't one of these came from
 * `receiveJsonBody` itself (malformed JSON, a wrong-typed field) and carries
 * raw `kotlinx.serialization` exception text — never safe to put on the wire.
 */
private val bulkValidationErrorCodes =
    setOf(BAD_REQUEST_ERROR, "too_many_pois", "bad_group_size", "bad_date_window", "end_before_start")

internal fun Route.bulkAvailabilityRoutes(
    controller: BulkAvailabilityController,
    config: BulkAvailabilityConfig,
    rateLimit: IpRateLimiter = IpRateLimiter(perMinute = config.ipPoiLimitPerMinute),
) {
    route("/api") {
        route("/pois") {
            post("/availability/stays") {
                val dto = call.receiveJsonBody<StayCheckRequestDto>().orRefuse(call) ?: return@post
                val siteTypes = call.siteTypesOrRefuse(dto.siteTypes) ?: return@post
                val request = validatedOrRefuse(call) { dto.validated(config, siteTypes) } ?: return@post
                if (throttled(rateLimit, request.poiIds.size)) return@post

                call.respondEventStream(config.streamHeartbeat) {
                    controller.stayAnswers(request).collect { item ->
                        when (item) {
                            is StayStreamItem.Answer -> send(StayAnswerDto.EVENT, StayAnswerDto.serializer(), item.answer)
                            is StayStreamItem.Done ->
                                send(StayCheckDoneDto.EVENT, StayCheckDoneDto.serializer(), StayCheckDoneDto(item.outcome))
                        }
                    }
                }
            }.describeApi(
                tag = "availability",
                summary = "How one stay looks at many campground POIs, streamed as each answers",
                description = stayCheckDescription(config),
            ).access(RouteAccess.Anonymous)
        }
    }
}

/**
 * What the stay check's documentation says, built from the config and the enums
 * the body is written from so the published text cannot drift from either: each
 * vocabulary is read off its serializer, each bound off the config that enforces it.
 */
internal fun stayCheckDescription(config: BulkAvailabilityConfig): String =
    "Body: { poi_ids: [pois.id, ...1..${config.maxPois}], start_date, end_date, " +
        "site_type?, group_size?, max_age_seconds? }. Answers 200 as a text/event-stream: one " +
        "`${StayAnswerDto.EVENT}` event per requested POI as it lands — POIs start in " +
        "request order, so send the nearest first — each with an `outcome` of ${wireNameList<StayOutcome>()}. " +
        "`${StayOutcome.ANSWERED.wireName()}` carries `stay`: how the stay looks there " +
        "(${wireNameList<StayAvailabilityStatus>()}), and how many sites are open every night from " +
        "start_date up to end_date. `${StayOutcome.REFUSED.wireName()}` means the dates cannot work there, " +
        "with a `reason`. `${StayOutcome.FAILED.wireName()}` carries a `reason` and whether asking again " +
        "can help (`retryable`). `${StayOutcome.TIMED_OUT.wireName()}` means the POI did not answer in time. " +
        "Then one `${StayCheckDoneDto.EVENT}` event, whose `outcome` is ${wireNameList<StayCheckOutcome>()}: " +
        "`${StayCheckOutcome.DEADLINE_REACHED.wireName()}` when the stream's deadline cut the check short and " +
        "the server answered `${StayOutcome.TIMED_OUT.wireName()}` for the POIs still waiting; then the " +
        "stream closes. A POI that takes longer than ${config.perPoiTimeout} answers " +
        "`${StayOutcome.TIMED_OUT.wireName()}`, and so does every POI yet to answer once the stream reaches " +
        "${config.streamTimeout}; a quiet stream sends a keep-alive comment every ${config.streamHeartbeat}. " +
        "`max_age_seconds` is how old a stored observation may be and still answer, rather than asking " +
        "the providers again. It is clamped into the server's range, not obeyed: raised to " +
        "${config.minMaxAge.seconds} seconds if lower (so a caller cannot force a refetch of every " +
        "campground on every request), lowered to ${config.defaultMaxAge.seconds} seconds if higher, and " +
        "${config.defaultMaxAge.seconds} seconds when left out. An out-of-range value is never a 400. " +
        "A refused request answers JSON with the status before any event is sent. Each requested POI is " +
        "charged against the caller's ${config.ipPoiLimitPerMinute} a minute; over it answers 503 " +
        "`ip_throttled`. `site_type` accepts: $siteTypeWireList. Per-campsite detail for one POI is " +
        "`GET /api/pois/{id}/campsites/availability`."

/**
 * Refuses with 503 when asking about [poiCount] more campgrounds would put the
 * caller over the per-caller budget; true when it did. Checked
 * once the request is known valid, since what it costs is its size — a refused
 * malformed body never reaches a provider, so it spends nothing.
 */
private suspend fun RoutingContext.throttled(
    rateLimit: IpRateLimiter,
    poiCount: Int,
): Boolean {
    if (rateLimit.allow(call.request.origin.remoteHost, cost = poiCount)) return false
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
        maxAge = clampedMaxAge(maxAgeSeconds, config),
    )
}

/**
 * The age a stay check may serve a stored observation at: what the caller asked for,
 * held to [BulkAvailabilityConfig.minMaxAge] .. [BulkAvailabilityConfig.defaultMaxAge],
 * and the ceiling when it did not ask. Clamped rather than refused because an age out of
 * range is not a malformed request — the caller wants "as fresh as you will give me" or
 * "whatever you have", and gets exactly that. The floor stops a caller forcing zero and
 * refetching every campground on every request; the ceiling keeps the server in charge
 * of how stale is too stale to serve at all.
 */
private fun clampedMaxAge(
    maxAgeSeconds: Long?,
    config: BulkAvailabilityConfig,
): Duration =
    maxAgeSeconds
        ?.let { Duration.ofSeconds(it).coerceIn(config.minMaxAge, config.defaultMaxAge) }
        ?: config.defaultMaxAge

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
