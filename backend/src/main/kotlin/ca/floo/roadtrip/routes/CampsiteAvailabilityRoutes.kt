package ca.floo.roadtrip.routes

import ca.floo.campsite.recgov.booker.api.DEFAULT_AVAILABILITY_DAYS
import ca.floo.campsite.recgov.booker.api.IpRateLimiter
import ca.floo.campsite.recgov.booker.api.MAX_AVAILABILITY_DAYS
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityEmptySchema
import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import ca.floo.roadtrip.models.api.BulkAvailEntrySchema
import ca.floo.roadtrip.models.api.BulkAvailRequestSchema
import ca.floo.roadtrip.models.api.BulkAvailResponseSchema
import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.service.booking.AvailabilityRequest
import ca.floo.roadtrip.service.booking.AvailableDatesRequest
import ca.floo.roadtrip.service.booking.BookingProviderError
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.ProviderRefParser
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.LocalDate

private val log = LoggerFactory.getLogger("CampsiteAvailabilityRoutes")

// Bulk endpoint guardrails. The single-id endpoint already serves the drawer;
// bulk is for the route-planner card list which scores N campgrounds against
// "are there sites for me on Jul 4 for 3 nights?" Cap nights at 14 (any
// realistic trip leg) and ids at 50 (one per visible card row).
private const val MAX_BULK_IDS = 50
private const val MAX_NIGHTS = 14

// Multi-night classifier upper bound. 31 covers any realistic stay; longer
// windows would need a sliding-window optimization in the per-day classifier.
private const val MAX_MIN_NIGHTS = 31

// Per-IP rate-limit budget. Cross-provider — one bucket regardless of which
// adapter ends up answering. See [IpRateLimiter] for the token-bucket math.
private const val IP_RATE_LIMIT_PER_MINUTE = 30
private const val IP_THROTTLE_RETRY_AFTER_S = 30
private const val UPSTREAM_RATE_LIMITED_RETRY_AFTER_S = 60
private const val UPSTREAM_BLOCKED_RETRY_AFTER_S = 300
private const val UPSTREAM_5XX_RETRY_AFTER_S = 30

/**
 * Unified campsite availability endpoint, keyed by `pois.id`. Dispatch to the
 * upstream is the registry's job — this route just parses inputs, looks up
 * the right [BookingProvider], and serializes the result.
 *
 * See [BookingProviderRegistry] / `docs/booking-providers.md` for the
 * provider-port architecture. Adding a new upstream is one new adapter file
 * + one registry wiring line; this file does not change.
 */
fun Route.campsiteAvailabilityRoutes(
    providerRefs: CampsiteProviderRepo,
    bookingProviders: BookingProviderRegistry,
) {
    val rateLimit = IpRateLimiter(perMinute = IP_RATE_LIMIT_PER_MINUTE)

    get("/api/campsite/availability/{poi_id}", {
        tags = listOf("campsite-availability")
        summary = "Per-day availability for one campground (cached, provider-dispatched)"
        description =
            "Path key is `pois.id`. Backend dispatches to the booking-provider " +
            "adapter registered for that POI's source (rec.gov, Aspira PC/BC/WA, " +
            "Camis stub). Response shape is provider-stable; provider-specific " +
            "extras (`campground_id` for rec.gov; `host`/`map_id` for Aspira) " +
            "are additive. Optional `?start=YYYY-MM-DD` shifts the window " +
            "(default: today); capped at `capabilities.bookingHorizonDays` " +
            "ahead of today, per provider. Optional `?min_nights=N` (1..31, " +
            "default 1) classifies each day under same-site multi-night " +
            "semantics: 'available' means at least one site is open for all " +
            "N consecutive nights starting that day."
        response {
            code(HttpStatusCode.BadRequest) {
                description = "Bad POI id, invalid days, or start out of range."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No campground/provider row exists for that POI id."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Rate limited or upstream availability service unavailable."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val poiId =
            call.parameters["poi_id"]?.toLongOrNull()
                ?: return@get call.respondAvailabilityError("bad_poi_id", HttpStatusCode.BadRequest)

        val days =
            call.request.queryParameters["days"]?.toIntOrNull()
                ?: DEFAULT_AVAILABILITY_DAYS
        if (days !in 1..MAX_AVAILABILITY_DAYS) {
            call.respondAvailabilityError("bad_days", HttpStatusCode.BadRequest)
            return@get
        }

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondAvailabilityError(
                "ip_throttled",
                HttpStatusCode.ServiceUnavailable,
                retryAfterS = IP_THROTTLE_RETRY_AFTER_S,
            )
            return@get
        }

        val row = providerRefs.findProviderRef(poiId)
        if (row == null) {
            call.respondAvailabilityError("unknown_campground", HttpStatusCode.NotFound)
            return@get
        }
        val provider = bookingProviders.forPoi(row)
        if (provider == null) {
            // Source has no adapter wired (e.g. legacy rows pre-registry). The
            // drawer's hasAvailability gate should prevent this from being
            // called for non-bookable rows; respond empty rather than 5xx.
            call.respondAvailabilityJson(AvailabilityEmptySchema())
            return@get
        }
        val ref = ProviderRefParser.parse(row.providerRefJson)
        if (ref == null) {
            call.respondAvailabilityJson(AvailabilityEmptySchema())
            return@get
        }

        val force = call.request.queryParameters["force"] == "1"
        val today = LocalDate.now(java.time.ZoneOffset.UTC)
        val start =
            when (val parsed = parseStartParam(call.request.queryParameters["start"], today, provider.capabilities.bookingHorizonDays)) {
                is StartParam.Ok -> parsed.value
                StartParam.Invalid -> {
                    call.respondAvailabilityError("bad_start", HttpStatusCode.BadRequest)
                    return@get
                }
            }
        val minNights =
            call.request.queryParameters["min_nights"]
                ?.toIntOrNull()
                ?.coerceIn(1, MAX_MIN_NIGHTS)
                ?: 1

        try {
            val response =
                provider.availability(
                    AvailabilityRequest(
                        ref = ref,
                        start = start,
                        days = days,
                        minNights = minNights,
                        force = force,
                    ),
                )
            call.respondAvailabilityJson(response)
        } catch (e: BookingProviderError) {
            val (status, error) = mapProviderError(e)
            log.info(
                "availability poi={} provider={} failed: {}",
                poiId,
                provider.id,
                e.message,
            )
            call.respondAvailabilityJson(error, status)
        }
    }

    // POST /api/campsite/availability/bulk
    //
    // Trip-planner endpoint. The FE has a list of campgrounds along the
    // active corridor and wants to know "for these N campgrounds, which
    // dates between [start, start+nights-1] have at least one bookable
    // site?" Mixed providers in one call are fine — each id is dispatched
    // by the registry independently.
    post("/api/campsite/availability/bulk", {
        tags = listOf("campsite-availability")
        summary = "Bulk per-day availability for many campgrounds in a date window (poi-id keyed)"
        description =
            "Body: { ids: number[], start: 'YYYY-MM-DD', nights: 1..$MAX_NIGHTS }. " +
            "Returns one entry per id with an HTTP-style `status` and the dates inside " +
            "the window where at least one site is bookable. Mixed providers OK."
        request {
            body<BulkAvailRequestSchema> {
                mediaTypes(ContentType.Application.Json)
                example("3-night July 4 weekend") {
                    value =
                        BulkAvailRequestSchema(
                            ids = listOf(12345L, 67890L),
                            start = "2026-07-04",
                            nights = 3,
                        )
                }
            }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "One entry per id. status==200 → available_dates is meaningful."
                body<BulkAvailResponseSchema> {
                    mediaTypes(ContentType.Application.Json)
                    example("mixed") {
                        value =
                            BulkAvailResponseSchema(
                                start = "2026-07-04",
                                nights = 3,
                                results =
                                    listOf(
                                        BulkAvailEntrySchema(12345L, 200, listOf("2026-07-04", "2026-07-06")),
                                        BulkAvailEntrySchema(67890L, 200, emptyList()),
                                        BulkAvailEntrySchema(99999L, 503, emptyList()),
                                    ),
                            )
                    }
                }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed body, missing fields, or limits exceeded."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Rate limited."
                body<ApiErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val req =
            try {
                Json.decodeFromString(BulkAvailRequestSchema.serializer(), call.receiveText())
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
        if (req.nights !in 1..MAX_NIGHTS) {
            call.respondApiError(
                "bad_nights",
                HttpStatusCode.BadRequest,
                detail = "nights must be in 1..$MAX_NIGHTS",
            )
            return@post
        }
        val start =
            try {
                LocalDate.parse(req.start)
            } catch (e: Exception) {
                call.respondApiError("bad_start", HttpStatusCode.BadRequest, detail = "start must be YYYY-MM-DD")
                return@post
            }

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondApiError(
                "ip_throttled",
                HttpStatusCode.ServiceUnavailable,
                retryAfterS = IP_THROTTLE_RETRY_AFTER_S,
            )
            return@post
        }

        val rowsById = providerRefs.findProviderRefs(req.ids)

        val results =
            coroutineScope {
                req.ids
                    .map { id ->
                        async { fetchOneBulk(id, rowsById[id], bookingProviders, start, req.nights) }
                    }.awaitAll()
            }

        call.respondAvailabilityJson(
            BulkAvailResponseSchema(
                start = req.start,
                nights = req.nights,
                results = results,
            ),
        )
    }
}

private suspend fun fetchOneBulk(
    poiId: Long,
    row: CampsiteProviderRefRow?,
    bookingProviders: BookingProviderRegistry,
    start: LocalDate,
    nights: Int,
): BulkAvailEntrySchema {
    if (row == null) {
        return BulkAvailEntrySchema(id = poiId, status = 404, available_dates = emptyList())
    }
    val provider =
        bookingProviders.forPoi(row)
            ?: return BulkAvailEntrySchema(id = poiId, status = 422, available_dates = emptyList())
    val ref =
        ProviderRefParser.parse(row.providerRefJson)
            ?: return BulkAvailEntrySchema(id = poiId, status = 422, available_dates = emptyList())

    return try {
        val dates =
            provider.availableDates(
                AvailableDatesRequest(ref = ref, start = start, nights = nights),
            )
        BulkAvailEntrySchema(id = poiId, status = 200, available_dates = dates)
    } catch (e: BookingProviderError) {
        log.info("bulk availability poi={} provider={} failed: {}", poiId, provider.id, e.message)
        BulkAvailEntrySchema(id = poiId, status = httpStatusFor(e), available_dates = emptyList())
    }
}

/**
 * Result of parsing the `?start=YYYY-MM-DD` query param against the provider's
 * booking horizon. Sealed so the route can branch on it without re-checking
 * any null state.
 */
internal sealed class StartParam {
    data class Ok(
        val value: LocalDate,
    ) : StartParam()

    /** Malformed date, in the past, or beyond the provider's booking horizon. */
    object Invalid : StartParam()
}

/**
 * Parse `?start=` into a [StartParam]. Null/missing means "default to today."
 * Anything outside `[today, today + horizonDays]` is [StartParam.Invalid] —
 * the upstream wouldn't have data for it either way.
 */
internal fun parseStartParam(
    raw: String?,
    today: LocalDate,
    horizonDays: Int,
): StartParam {
    if (raw == null) return StartParam.Ok(today)
    val parsed = runCatching { LocalDate.parse(raw) }.getOrNull() ?: return StartParam.Invalid
    if (parsed.isBefore(today)) return StartParam.Invalid
    if (parsed.isAfter(today.plusDays(horizonDays.toLong()))) return StartParam.Invalid
    return StartParam.Ok(parsed)
}

/** Map the typed provider error to (HTTP status, AvailabilityErrorSchema). */
private fun mapProviderError(e: BookingProviderError): Pair<HttpStatusCode, AvailabilityErrorSchema> =
    when (e) {
        is BookingProviderError.RateLimited ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("rate_limited", retryAfterS = UPSTREAM_RATE_LIMITED_RETRY_AFTER_S)
        is BookingProviderError.UpstreamBlocked ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_blocked", retryAfterS = UPSTREAM_BLOCKED_RETRY_AFTER_S)
        is BookingProviderError.UpstreamUnavailable ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_5xx", retryAfterS = UPSTREAM_5XX_RETRY_AFTER_S)
        is BookingProviderError.Unsupported ->
            HttpStatusCode.NotImplemented to availabilityErrorDto("unsupported")
        is BookingProviderError.WrongRefType ->
            // Programmer error, not a user error. Surface as 500 so it shows up in metrics.
            HttpStatusCode.InternalServerError to availabilityErrorDto("provider_misconfigured")
    }

/** Numeric status for the bulk endpoint's per-id `status` field. */
private fun httpStatusFor(e: BookingProviderError): Int =
    when (e) {
        is BookingProviderError.RateLimited -> 429
        is BookingProviderError.Unsupported -> 422
        is BookingProviderError.WrongRefType -> 500
        else -> 503
    }

private suspend fun ApplicationCall.respondAvailabilityError(
    error: String,
    status: HttpStatusCode,
    retryAfterS: Int? = null,
) {
    respondAvailabilityJson(availabilityErrorDto(error, retryAfterS), status)
}

private suspend fun ApplicationCall.respondApiError(
    error: String,
    status: HttpStatusCode,
    detail: String? = null,
    retryAfterS: Int? = null,
) {
    respondAvailabilityJson(ApiErrorSchema(error = error, detail = detail, retry_after_s = retryAfterS), status)
}

private suspend inline fun <reified T> ApplicationCall.respondAvailabilityJson(
    value: T,
    status: HttpStatusCode = HttpStatusCode.OK,
) {
    respondText(encodeAvailabilityJson(value), ContentType.Application.Json, status)
}
