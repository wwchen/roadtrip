package ca.floo.roadtrip.routes

import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.models.Reservable
import ca.floo.roadtrip.models.ReservableId
import ca.floo.roadtrip.models.ReservableType
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityEmptySchema
import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import ca.floo.roadtrip.models.api.BulkAvailEntrySchema
import ca.floo.roadtrip.models.api.BulkAvailRequestSchema
import ca.floo.roadtrip.models.api.BulkAvailResponseSchema
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.api.AvailabilityCacheBlock
import ca.floo.roadtrip.service.api.DayClassification
import ca.floo.roadtrip.service.api.ReservableAvailabilityFetchService
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.availabilityResponseDto
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.service.booking.AvailableDatesRequest
import ca.floo.roadtrip.service.booking.BookingProviderError
import ca.floo.roadtrip.service.booking.BookingProviderRegistry
import ca.floo.roadtrip.service.booking.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.booking.CatalogReservableRef
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("AvailabilityRoutes")

// Bulk endpoint guardrails. The single-id endpoint already serves the drawer;
// bulk is for the route-planner card list which scores N campgrounds against
// "which dates in this window have bookable sites?" Cap window length at 14
// (any realistic trip leg) and ids at 50 (one per visible card row).
private const val MAX_BULK_IDS = 50
private const val MAX_BULK_WINDOW_DAYS = 14
private const val MAX_AVAILABILITY_DAYS: Int = 60

// Per-IP rate-limit budget. Cross-provider: one bucket regardless of which
// adapter ends up answering.
private const val IP_RATE_LIMIT_PER_MINUTE = 30
private const val IP_THROTTLE_RETRY_AFTER_S = 30
private const val UPSTREAM_RATE_LIMITED_RETRY_AFTER_S = 60
private const val UPSTREAM_BLOCKED_RETRY_AFTER_S = 300
private const val UPSTREAM_5XX_RETRY_AFTER_S = 30

/**
 * Unified availability endpoints. Dispatch to the upstream is the registry's
 * job; this route parses inputs, looks up the right [BookingProvider], and
 * serializes the result.
 *
 * See [BookingProviderRegistry] / `docs/booking-providers.md` for the
 * provider-port architecture. Adding a new upstream is one new adapter file
 * + one registry wiring line; this file does not change.
 */
fun Route.availabilityRoutes(
    providerRefs: CampsiteProviderRepo,
    bookingProviders: BookingProviderRegistry,
    reservables: ReservableRepo,
    snapshots: AvailabilitySnapshotRepo? = null,
) {
    val rateLimit = IpRateLimiter(perMinute = IP_RATE_LIMIT_PER_MINUTE)
    val reservableAvailabilityFetches = ReservableAvailabilityFetchService(snapshots)

    suspend fun ApplicationCall.handlePoiAvailability(poiIdParam: String) {
        val poiId =
            parameters[poiIdParam]?.toLongOrNull()
                ?: return respondAvailabilityError("bad_poi_id", HttpStatusCode.BadRequest)

        val ip = request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            respondAvailabilityError(
                "ip_throttled",
                HttpStatusCode.ServiceUnavailable,
                retryAfterS = IP_THROTTLE_RETRY_AFTER_S,
            )
            return
        }

        val row = providerRefs.findProviderRef(poiId)
        if (row == null) {
            respondAvailabilityError("unknown_campground", HttpStatusCode.NotFound)
            return
        }
        val provider = bookingProviders.forPoi(row)
        if (provider == null) {
            // Source has no adapter wired (e.g. legacy rows pre-registry). The
            // drawer's hasAvailability gate should prevent this from being
            // called for non-bookable rows; respond empty rather than 5xx.
            respondAvailabilityJson(AvailabilityEmptySchema())
            return
        }
        val ref = ProviderRefParser.parse(row.providerRefJson)
        if (ref == null) {
            respondAvailabilityJson(AvailabilityEmptySchema())
            return
        }

        val query = parseAvailabilityWindow(provider.capabilities.bookingHorizonDays)
        if (query == null) {
            respondAvailabilityError("bad_date_window", HttpStatusCode.BadRequest)
            return
        }

        try {
            val siteTypes = queryValues("site_type", "siteType")
            val catalogRows =
                reservables
                    .findByPoi(poiId, ReservableType.SITE)
                    .filterBySiteTypes(siteTypes)
            if (siteTypes.isNotEmpty() && catalogRows.isEmpty()) {
                respondAvailabilityJson(emptyPoiAvailability(ref, query.startDate, query.endDate))
                return
            }
            val catalogRefs =
                catalogRows
                    .map { it.toCatalogReservableRef() }
            val response =
                provider.catalogAvailability(
                    CatalogAvailabilityRequest(
                        ref = ref,
                        reservables = catalogRefs,
                        startDate = query.startDate,
                        endDate = query.endDate,
                        force = query.force,
                    ),
                )
            respondAvailabilityJson(response)
        } catch (e: BookingProviderError) {
            val (status, error) = mapProviderError(e)
            log.info(
                "availability poi={} provider={} failed: {}",
                poiId,
                provider.id,
                e.message,
            )
            respondAvailabilityJson(error, status)
        }
    }

    get("/api/campsite/availability/{poi_id}", {
        tags = listOf("campsite-availability")
        summary = "Deprecated: per-day availability for one campground"
        description =
            "Deprecated legacy path; prefer `/api/poi/{poi_id}/availability` " +
            "for POI-scoped availability or `/api/reservable/{rid}/availability` " +
            "for reservable-scoped availability. Path key is `pois.id`. " +
            "Backend dispatches to the booking-provider " +
            "adapter registered for that POI's source (rec.gov, Aspira PC/BC/WA, " +
            "Camis stub). Response shape is provider-stable; provider-specific " +
            "extras (`campground_id` for rec.gov; `host`/`map_id` for Aspira) " +
            "are additive. Optional `start_date`/`end_date` define an exclusive " +
            "date window. Missing start_date defaults to today UTC; missing " +
            "end_date defaults to start_date + 7 days. The window is capped at " +
            "`capabilities.bookingHorizonDays` ahead of today, per provider."
        request {
            queryParameter<String>("start_date") { description = "YYYY-MM-DD; default is today UTC." }
            queryParameter<String>("end_date") { description = "Exclusive YYYY-MM-DD; default is start_date + 7 days." }
            queryParameter<String>("force") { description = "Set to 1 to bypass provider cache." }
            queryParameter<String>("site_type") { description = "Exact site type filter. Repeat or comma-separate for OR." }
        }
        response {
            code(HttpStatusCode.BadRequest) {
                description = "Bad POI id or invalid date window."
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
        call.handlePoiAvailability("poi_id")
    }

    get("/api/poi/{poi_id}/availability", {
        tags = listOf("availability")
        summary = "Per-day availability for one campground POI (cached, provider-dispatched)"
        description =
            "Path key is `pois.id`. " +
            "Optional `site_type` filters the linked reservable catalog before " +
            "classification, so `available_reservable_ids` and counts reflect only " +
            "matching site rows."
        request {
            queryParameter<String>("start_date") { description = "YYYY-MM-DD; default is today UTC." }
            queryParameter<String>("end_date") { description = "Exclusive YYYY-MM-DD; default is start_date + 7 days." }
            queryParameter<String>("force") { description = "Set to 1 to bypass provider cache." }
            queryParameter<String>("site_type") { description = "Exact site type filter. Repeat or comma-separate for OR." }
        }
        response {
            code(HttpStatusCode.BadRequest) {
                description = "Bad POI id or invalid date window."
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
        call.handlePoiAvailability("poi_id")
    }

    get("/api/reservable/{rid}/availability", {
        tags = listOf("campsite-availability", "reservable")
        summary = "Per-day availability for one reservable"
        description =
            "Path key is RFC 0008 composite id `{type}:{vendor}:{vendor_id}`, " +
            "for example `site:recgov:330257`. The route finds the linked " +
            "campground POI, dispatches to its BookingProvider, and returns " +
            "the same availability response shape narrowed to that one site."
        request {
            pathParameter<String>("rid") { description = "{type}:{vendor}:{vendor_id}" }
            queryParameter<String>("start_date") { description = "YYYY-MM-DD; default is today UTC." }
            queryParameter<String>("end_date") { description = "Exclusive YYYY-MM-DD; default is start_date + 7 days." }
            queryParameter<String>("force") { description = "Set to 1 to bypass provider cache." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Availability for one reservable."
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed reservable id or invalid date window."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No reservable or linked campground provider row exists."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotImplemented) {
                description = "The reservable's provider has no per-reservable availability adapter yet."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.ServiceUnavailable) {
                description = "Rate limited or upstream availability service unavailable."
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
        }
    }) {
        val rid =
            call.parameters["rid"]
                ?.let(ReservableId::parse)
                ?: return@get call.respondAvailabilityError("bad_rid", HttpStatusCode.BadRequest)
        val row =
            reservables.findByRid(rid)
                ?: return@get call.respondAvailabilityError("not_found", HttpStatusCode.NotFound)

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondAvailabilityError(
                "ip_throttled",
                HttpStatusCode.ServiceUnavailable,
                retryAfterS = IP_THROTTLE_RETRY_AFTER_S,
            )
            return@get
        }

        val poiIds = reservables.poiIdsForReservable(row.id)
        val rowsById = providerRefs.findProviderRefs(poiIds)
        val parent =
            poiIds
                .asSequence()
                .mapNotNull { rowsById[it] }
                .firstOrNull { bookingProviders.forPoi(it) != null && ProviderRefParser.parse(it.providerRefJson) != null }
                ?: return@get call.respondAvailabilityError("unknown_campground", HttpStatusCode.NotFound)
        val provider = bookingProviders.forPoi(parent)!!
        val parentRef = ProviderRefParser.parse(parent.providerRefJson)!!
        val ref = row.providerRefForReservable(parentRef)

        val query = call.parseAvailabilityWindow(provider.capabilities.bookingHorizonDays)
        if (query == null) {
            call.respondAvailabilityError("bad_date_window", HttpStatusCode.BadRequest)
            return@get
        }

        try {
            val response =
                reservableAvailabilityFetches.fetch(
                    ReservableAvailabilityFetchService.Request(
                        reservableId = row.id,
                        reservableRid = rid.encode(),
                        provider = provider,
                        ref = ref,
                        vendorId = rid.vendorId,
                        startDate = query.startDate,
                        endDate = query.endDate,
                        force = query.force,
                    ),
                )
            call.respondAvailabilityJson(response)
        } catch (e: BookingProviderError) {
            val (status, error) = mapProviderError(e)
            log.info(
                "reservable availability rid={} parent_poi={} provider={} failed: {}",
                rid.encode(),
                parent.poiId,
                provider.id,
                e.message,
            )
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
            "Body: { ids: number[], start_date: 'YYYY-MM-DD', end_date: 'YYYY-MM-DD' }. " +
            "Returns one entry per id with an HTTP-style `status` and the dates inside " +
            "the window where at least one site is available on each date. Mixed providers OK."
        request {
            body<BulkAvailRequestSchema> {
                mediaTypes(ContentType.Application.Json)
                example("3-night July 4 weekend") {
                    value =
                        BulkAvailRequestSchema(
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
                body<BulkAvailResponseSchema> {
                    mediaTypes(ContentType.Application.Json)
                    example("mixed") {
                        value =
                            BulkAvailResponseSchema(
                                startDate = "2026-07-04",
                                endDate = "2026-07-07",
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
        val days =
            ChronoUnit.DAYS
                .between(start, end)
                .toInt()
        if (days !in 1..MAX_BULK_WINDOW_DAYS) {
            call.respondApiError(
                "bad_date_window",
                HttpStatusCode.BadRequest,
                detail = "date window must be 1..$MAX_BULK_WINDOW_DAYS days",
            )
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
                        async { fetchOneBulk(id, rowsById[id], bookingProviders, start, end) }
                    }.awaitAll()
            }

        call.respondAvailabilityJson(
            BulkAvailResponseSchema(
                startDate = req.startDate,
                endDate = req.endDate,
                results = results,
            ),
        )
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

private fun Reservable.toCatalogReservableRef(): CatalogReservableRef =
    CatalogReservableRef(
        rid = rid.encode(),
        vendorId = rid.vendorId,
        mapId = aspiraProviderRefLong("mapId"),
        resourceLocationId = aspiraProviderRefLong("resourceLocationId"),
    )

private fun Reservable.providerRefForReservable(parentRef: ProviderRef): ProviderRef =
    when (parentRef) {
        is ProviderRef.Aspira ->
            parentRef.copy(
                mapId = aspiraProviderRefLong("mapId") ?: parentRef.mapId,
                resourceLocationId = aspiraProviderRefLong("resourceLocationId") ?: parentRef.resourceLocationId,
            )
        else -> parentRef
    }

private fun Reservable.aspiraProviderRefLong(key: String): Long? =
    (providerRef as? JsonObject)
        ?.get(key)
        ?.jsonPrimitive
        ?.longOrNull

private fun emptyPoiAvailability(
    ref: ProviderRef,
    startDate: LocalDate,
    endDate: LocalDate,
) = availabilityResponseDto(
    provider =
        when (ref) {
            is ProviderRef.RecGov -> "recgov"
            is ProviderRef.Aspira -> "aspira"
            is ProviderRef.Camis -> "camis"
        },
    startDate = startDate,
    endDate = endDate,
    perDay =
        (0 until ChronoUnit.DAYS.between(startDate, endDate).toInt()).map { offset ->
            DayClassification(
                date = startDate.plusDays(offset.toLong()).toString(),
                status = "closed",
                availableCount = 0,
                total = 0,
            )
        },
    state = "empty",
    summary = "No availability data",
    seasonBlock = null,
    cacheBlock = AvailabilityCacheBlock(hit = true, ageSeconds = 0, ttlSeconds = 0),
    campgroundId = (ref as? ProviderRef.RecGov)?.recgovId,
    mapId = (ref as? ProviderRef.Aspira)?.mapId?.toString(),
)

private suspend fun fetchOneBulk(
    poiId: Long,
    row: CampsiteProviderRefRow?,
    bookingProviders: BookingProviderRegistry,
    startDate: LocalDate,
    endDate: LocalDate,
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
                AvailableDatesRequest(ref = ref, startDate = startDate, endDate = endDate),
            )
        BulkAvailEntrySchema(id = poiId, status = 200, available_dates = dates)
    } catch (e: BookingProviderError) {
        log.info("bulk availability poi={} provider={} failed: {}", poiId, provider.id, e.message)
        BulkAvailEntrySchema(id = poiId, status = httpStatusFor(e), available_dates = emptyList())
    }
}

/**
 * Result of parsing the `?start_date=YYYY-MM-DD` query param against the provider's
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
 * Parse `?start_date=` into a [StartParam]. Null/missing means "default to today."
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

private data class AvailabilityWindowQuery(
    val startDate: LocalDate,
    val endDate: LocalDate,
    val force: Boolean,
) {
    val days: Int = ChronoUnit.DAYS.between(startDate, endDate).toInt()
}

private fun ApplicationCall.parseAvailabilityWindow(
    bookingHorizonDays: Int,
    defaultDays: Int = 7,
): AvailabilityWindowQuery? {
    if (listOf("start", "days", "min_nights", "minNights").any { request.queryParameters[it] != null }) return null
    val today = LocalDate.now(ZoneOffset.UTC)
    val start =
        when (val parsed = parseStartParam(request.queryParameters["start_date"], today, bookingHorizonDays)) {
            is StartParam.Ok -> parsed.value
            StartParam.Invalid -> return null
        }
    val end =
        request.queryParameters["end_date"]
            ?.let { raw -> runCatching { LocalDate.parse(raw) }.getOrNull() ?: return null }
            ?: start.plusDays(defaultDays.toLong())
    if (!end.isAfter(start)) return null
    if (end.isAfter(today.plusDays(bookingHorizonDays.toLong()))) return null
    val days = ChronoUnit.DAYS.between(start, end).toInt()
    if (days !in 1..MAX_AVAILABILITY_DAYS) return null
    val force = request.queryParameters["force"] == "1"
    return AvailabilityWindowQuery(startDate = start, endDate = end, force = force)
}

private fun ApplicationCall.queryValues(vararg names: String): List<String> =
    names
        .flatMap { name -> request.queryParameters.getAll(name).orEmpty() }
        .flatMap { value -> value.split(",") }
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()

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
