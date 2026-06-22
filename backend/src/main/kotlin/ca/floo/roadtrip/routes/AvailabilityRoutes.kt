package ca.floo.roadtrip.routes

import ca.floo.roadtrip.clients.aspira.AspiraException
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityErrorDto
import ca.floo.roadtrip.models.api.BulkAvailabilityEntryDto
import ca.floo.roadtrip.models.api.BulkAvailabilityRequestDto
import ca.floo.roadtrip.models.api.BulkAvailabilityResponseDto
import ca.floo.roadtrip.models.api.PoiReservablesAvailabilityResponseDto
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.service.availability.AvailabilityQueryService
import ca.floo.roadtrip.service.availability.AvailabilityService
import ca.floo.roadtrip.service.availability.AvailabilityServiceError
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderRegistry
import io.github.smiley4.ktorswaggerui.dsl.routing.get
import io.github.smiley4.ktorswaggerui.dsl.routing.post
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.plugins.origin
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("AvailabilityRoutes")

// Bulk endpoint guardrails. The single-id endpoint already serves the drawer;
// bulk is for the route-planner card list which scores N campgrounds against
// "which dates in this window have bookable sites?" Cap window length at 14
// (any realistic trip leg) and ids at 50 (one per visible card row).
private const val MAX_BULK_IDS = 50

// Per-IP rate-limit budget. Cross-provider: one bucket regardless of which
// adapter ends up answering.
private const val IP_RATE_LIMIT_PER_MINUTE = 30

/**
 * Unified availability endpoints. The route layer parses HTTP request shapes,
 * applies cross-route HTTP guardrails, resolves POI requests to reservable ids,
 * delegates availability lookup to [AvailabilityService], and serializes the result.
 *
 * See [ReservationProviderRegistry] / `docs/reservation-providers.md` for the
 * provider-port architecture. Adding a new upstream is one new adapter file
 * + one registry wiring line; this file does not change.
 */
internal fun Route.availabilityRoutes(
    availabilityService: AvailabilityService,
    routeService: AvailabilityQueryService,
) {
    val rateLimit = IpRateLimiter(perMinute = IP_RATE_LIMIT_PER_MINUTE)

    get("/api/poi/{poi_id}/reservables/availability", {
        tags = listOf("availability", "reservable")
        summary = "Per-reservable availability for one POI's reservables"
        description =
            "Path key is `pois.id`. Returns one availability envelope per reservable " +
            "linked to this POI — the same shape `/api/reservable/{rid}/availability` " +
            "returns for a single reservable. The FE fuses the per-reservable streams " +
            "into the campground week grid. " +
            "An empty `reservables` array means the POI has no online-bookable " +
            "reservables (walk-up / non-reservable); the drawer should hide the matrix. " +
            "Optional `site_type` filters the linked catalog before dispatch."
        request {
            pathParameter<Long>("poi_id") { description = "pois.id primary key" }
            queryParameter<String>("start_date") { description = "YYYY-MM-DD; default is today's local date." }
            queryParameter<String>("end_date") { description = "Exclusive YYYY-MM-DD; default is start_date + 7 days." }
            queryParameter<String>("force") { description = "Set to 1 to bypass provider cache." }
            queryParameter<String>("site_type") { description = "Exact site type filter. Repeat or comma-separate for OR." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Wrapped envelope. `reservables` is empty when none are linked."
                body<PoiReservablesAvailabilityResponseDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Bad POI id or invalid date window."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No active POI with that id."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Rate limited or upstream availability service unavailable."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val poiId =
            call.parameters["poi_id"]?.toLongOrNull()
                ?: return@get call.respondAvailabilityError("bad_poi_id", HttpStatusCode.BadRequest)

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondAvailabilityError(
                "ip_throttled",
                HttpStatusCode.ServiceUnavailable,
            )
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
        val force = call.forceQuery()

        try {
            call.respondAvailabilityJson(
                routeService.poiReservablesAvailability(
                    poiId = poiId,
                    startDate = startDate,
                    endDate = endDate,
                    force = force,
                    siteTypes = call.queryValues("site_type", "siteType"),
                ),
            )
        } catch (e: AvailabilityServiceError) {
            call.respondServiceAvailabilityError(e)
        } catch (e: ReservationProviderError) {
            val (status, error) = mapProviderError(e)
            log.info("poi reservables availability poi={} failed: {}", poiId, e.message)
            call.respondAvailabilityJson(error, status)
        }
    }

    get("/api/reservable/{rid}/availability", {
        tags = listOf("campsite-availability", "reservable")
        summary = "Per-day availability for one reservable"
        description =
            "Path key is RFC 0008 composite id `{type}:{vendor}:{vendor_id}`, " +
            "for example `site:recgov:330257`. The route finds the linked " +
            "campground POI, dispatches to its ReservationProvider, and returns " +
            "the same availability response shape narrowed to that one site."
        request {
            pathParameter<String>("rid") { description = "{type}:{vendor}:{vendor_id}" }
            queryParameter<String>("start_date") { description = "YYYY-MM-DD; default is today's local date." }
            queryParameter<String>("end_date") { description = "Exclusive YYYY-MM-DD; default is start_date + 7 days." }
            queryParameter<String>("force") { description = "Set to 1 to bypass provider cache." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Availability for one reservable."
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed reservable id or invalid date window."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No reservable or linked campground provider row exists."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotImplemented) {
                description = "The reservable's provider has no per-reservable availability adapter yet."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Rate limited or upstream availability service unavailable."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val rid =
            call.parameters["rid"]
                ?.let(ReservableId::parse)
                ?: return@get call.respondAvailabilityError("bad_rid", HttpStatusCode.BadRequest)

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondAvailabilityError(
                "ip_throttled",
                HttpStatusCode.ServiceUnavailable,
            )
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
        val force = call.forceQuery()

        try {
            call.respondAvailabilityJson(
                availabilityService.getByRid(
                    rid = rid,
                    startDate = startDate,
                    endDate = endDate,
                    force = force,
                ),
            )
        } catch (e: AvailabilityServiceError) {
            call.respondServiceAvailabilityError(e)
        } catch (e: ReservationProviderError) {
            val (status, error) = mapProviderError(e)
            log.info("reservable availability rid={} failed: {}", rid.encode(), e.message)
            call.respondAvailabilityJson(error, status)
        }
    }

    // POST /api/availability/bulk
    //
    // Trip-planner endpoint. The FE has a list of campgrounds along the
    // active corridor and wants to know "for these N campgrounds, which
    // dates in [start_date, end_date) have at least one bookable site?"
    // Mixed providers in one call are fine — each id is dispatched
    // by the registry independently.
    post("/api/availability/bulk", {
        tags = listOf("availability")
        summary = "Bulk per-day availability for many campgrounds in a date window (poi-id keyed)"
        description =
            "Deprecated. Body: { ids: number[], start_date: 'YYYY-MM-DD', end_date: 'YYYY-MM-DD' }. " +
            "Returns one entry per id with an HTTP-style `status` and the dates inside " +
            "the window where at least one site is available on each date. Mixed providers OK."
        request {
            body<BulkAvailabilityRequestDto> {
                mediaTypes(ContentType.Application.Json)
                example("3-night July 4 weekend") {
                    value =
                        BulkAvailabilityRequestDto(
                            ids = listOf(12345L, 67890L),
                            startDate = "2026-07-04",
                            endDate = "2026-07-07",
                        )
                }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "One entry per id. status==200 → available_dates is meaningful."
                body<BulkAvailabilityResponseDto> {
                    mediaTypes(ContentType.Application.Json)
                    example("mixed") {
                        value =
                            BulkAvailabilityResponseDto(
                                startDate = "2026-07-04",
                                endDate = "2026-07-07",
                                results =
                                    listOf(
                                        BulkAvailabilityEntryDto(12345L, 200, listOf("2026-07-04", "2026-07-06")),
                                        BulkAvailabilityEntryDto(67890L, 200, emptyList()),
                                        BulkAvailabilityEntryDto(99999L, 503, emptyList()),
                                    ),
                            )
                    }
                }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed body, missing fields, or limits exceeded."
                body<AvailabilityErrorDto> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Rate limited."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        call.markBulkAvailabilityDeprecated()
        val req =
            try {
                Json.decodeFromString(BulkAvailabilityRequestDto.serializer(), call.receiveText())
            } catch (e: Exception) {
                call.respondApiError("bad_request", HttpStatusCode.BadRequest, detail = e.message ?: "parse failed")
                return@post
            }

        if (req.ids.isEmpty() || req.ids.size > MAX_BULK_IDS) {
            call.respondApiError(
                "bad_ids",
                HttpStatusCode.BadRequest,
                detail = "need 1..$MAX_BULK_IDS ids, got ${req.ids.size}",
            )
            return@post
        }
        val start =
            try {
                LocalDate.parse(req.startDate)
            } catch (e: Exception) {
                call.respondApiError("bad_start_date", HttpStatusCode.BadRequest, detail = "start_date must be YYYY-MM-DD")
                return@post
            }
        val end =
            try {
                LocalDate.parse(req.endDate)
            } catch (e: Exception) {
                call.respondApiError("bad_end_date", HttpStatusCode.BadRequest, detail = "end_date must be YYYY-MM-DD")
                return@post
            }

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondApiError(
                "ip_throttled",
                HttpStatusCode.ServiceUnavailable,
            )
            return@post
        }

        try {
            call.respondAvailabilityJson(
                routeService.bulkAvailability(
                    ids = req.ids,
                    startDate = start,
                    endDate = end,
                ),
            )
        } catch (e: AvailabilityServiceError.BadDateWindow) {
            call.respondAvailabilityJson(
                availabilityErrorDto(e),
                HttpStatusCode.BadRequest,
            )
        }
    }
}

/**
 * Tiny per-IP token-bucket rate limiter. The limit's job is to make casual
 * scraping unprofitable, not survive a determined attacker.
 */
private class IpRateLimiter(
    private val perMinute: Int,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private data class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
    )

    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val refillPerMs = perMinute / 60_000.0

    fun allow(ip: String): Boolean {
        val now = nowMs()
        val bucket =
            buckets.compute(ip) { _, existing ->
                val b = existing ?: Bucket(perMinute.toDouble(), now)
                val delta = now - b.lastRefillMs
                b.tokens = (b.tokens + delta * refillPerMs).coerceAtMost(perMinute.toDouble())
                b.lastRefillMs = now
                b
            }!!
        return synchronized(bucket) {
            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                true
            } else {
                false
            }
        }
    }
}

/** Map the typed provider error to (HTTP status, AvailabilityErrorDto). */
internal fun mapProviderError(e: ReservationProviderError): Pair<HttpStatusCode, AvailabilityErrorDto> {
    val upstream = upstreamHttpStatus(e)
    return when (e) {
        is ReservationProviderError.RateLimited ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("rate_limited", upstreamStatus = upstream)
        is ReservationProviderError.UpstreamBlocked ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_blocked", upstreamStatus = upstream)
        is ReservationProviderError.UpstreamUnavailable ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_5xx", upstreamStatus = upstream)
        is ReservationProviderError.Unsupported ->
            HttpStatusCode.NotImplemented to availabilityErrorDto("unsupported")
        is ReservationProviderError.WrongRefType ->
            // Programmer error, not a user error. Surface as 500 so it shows up in metrics.
            HttpStatusCode.InternalServerError to availabilityErrorDto("provider_misconfigured")
    }
}

internal fun upstreamHttpStatus(e: ReservationProviderError): Int? {
    var t: Throwable? = e.cause
    while (t != null) {
        if (t is AspiraException) return t.httpStatus
        t = t.cause
    }
    return null
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

private suspend fun ApplicationCall.respondApiError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
) {
    respondAvailabilityJson(ApiErrorSchema(error = error, detail = detail), status)
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

private fun ApplicationCall.markBulkAvailabilityDeprecated() {
    response.headers.append("Deprecation", "true")
    response.headers.append(
        "Warning",
        "299 - \"/api/availability/bulk is deprecated; use POI/reservable availability service paths\"",
    )
}
