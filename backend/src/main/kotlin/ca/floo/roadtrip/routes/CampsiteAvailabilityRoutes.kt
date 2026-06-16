package ca.floo.roadtrip.routes

import ca.floo.campsite.recgov.booker.api.DEFAULT_AVAILABILITY_DAYS
import ca.floo.campsite.recgov.booker.api.IpRateLimiter
import ca.floo.campsite.recgov.booker.api.MAX_AVAILABILITY_DAYS
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
import ca.floo.roadtrip.models.api.PoiReservablesAvailabilityDateSchema
import ca.floo.roadtrip.models.api.PoiReservablesAvailabilityResponseSchema
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.api.AvailabilityDayDto
import ca.floo.roadtrip.service.api.ReservableAvailabilityFetchService
import ca.floo.roadtrip.service.api.availabilityErrorDto
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
    reservables: ReservableRepo,
    snapshots: AvailabilitySnapshotRepo? = null,
) {
    val rateLimit = IpRateLimiter(perMinute = IP_RATE_LIMIT_PER_MINUTE)
    val reservableAvailabilityFetches = ReservableAvailabilityFetchService(snapshots)

    suspend fun ApplicationCall.handlePoiAvailability(poiIdParam: String) {
        val poiId =
            parameters[poiIdParam]?.toLongOrNull()
                ?: return respondAvailabilityError("bad_poi_id", HttpStatusCode.BadRequest)

        val days =
            request.queryParameters["days"]?.toIntOrNull()
                ?: DEFAULT_AVAILABILITY_DAYS
        if (days !in 1..MAX_AVAILABILITY_DAYS) {
            respondAvailabilityError("bad_days", HttpStatusCode.BadRequest)
            return
        }

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

        val query = parseAvailabilityQuery(provider.capabilities.bookingHorizonDays)
        if (query == null) {
            respondAvailabilityError("bad_start", HttpStatusCode.BadRequest)
            return
        }

        try {
            val catalogRefs =
                reservables
                    .findByPoi(poiId, ReservableType.SITE)
                    .map { it.toCatalogReservableRef() }
            val response =
                provider.catalogAvailability(
                    CatalogAvailabilityRequest(
                        ref = ref,
                        reservables = catalogRefs,
                        start = query.start,
                        days = days,
                        minNights = query.minNights,
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
        call.handlePoiAvailability("poi_id")
    }

    get("/api/poi/{poi_id}/availability", {
        tags = listOf("campsite-availability")
        summary = "Per-day availability for one campground POI (cached, provider-dispatched)"
        description =
            "Path key is `pois.id`. This is the RFC 0008 POI-scoped alias for " +
            "`/api/campsite/availability/{poi_id}` and returns the same response shape."
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
        call.handlePoiAvailability("poi_id")
    }

    get("/api/poi/{poi_id}/reservables/availability", {
        tags = listOf("campsite-availability", "reservable")
        summary = "Available reservables grouped by date for one campground POI"
        description =
            "Returns a date-first availability matrix for one POI. Each date " +
            "contains the linked reservables available for that arrival date. " +
            "`site_type` filters the POI catalog before provider classification, " +
            "so the response can answer questions like 'which tent sites are " +
            "available next week?' without a per-site upstream loop."
        request {
            pathParameter<Long>("poi_id") { description = "pois.id primary key" }
            queryParameter<Int>("days") { description = "Window length, default 30, max 60." }
            queryParameter<String>("start") { description = "YYYY-MM-DD; default is today." }
            queryParameter<Int>("min_nights") { description = "Same-site stay length, 1..31." }
            queryParameter<String>("type") { description = "Reservable type, defaults to site." }
            queryParameter<String>("site_type") { description = "Exact site type filter. Repeat or comma-separate for OR." }
            queryParameter<String>("force") { description = "Set to 1 to bypass provider cache." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Available dates with matching reservables embedded."
                body<PoiReservablesAvailabilityResponseSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed POI id, type, days, or start."
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
        val type =
            parseReservableType(call.request.queryParameters["type"])
                ?: return@get call.respondAvailabilityError("bad_type", HttpStatusCode.BadRequest)

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

        val row =
            providerRefs.findProviderRef(poiId)
                ?: return@get call.respondAvailabilityError("unknown_campground", HttpStatusCode.NotFound)
        val provider =
            bookingProviders.forPoi(row)
                ?: return@get call.respondAvailabilityError("unsupported_provider", HttpStatusCode.NotImplemented)
        val ref =
            ProviderRefParser.parse(row.providerRefJson)
                ?: return@get call.respondAvailabilityError("bad_provider_ref", HttpStatusCode.InternalServerError)
        val query =
            call.parseAvailabilityQuery(provider.capabilities.bookingHorizonDays)
                ?: return@get call.respondAvailabilityError("bad_start", HttpStatusCode.BadRequest)

        val siteTypes = call.queryValues("site_type", "siteType")
        val rows =
            reservables
                .findByPoi(poiId, type)
                .filterBySiteTypes(siteTypes)

        if (rows.isEmpty()) {
            call.respondAvailabilityJson(
                PoiReservablesAvailabilityResponseSchema(
                    poiId = poiId,
                    type = type.encode(),
                    start = query.start.toString(),
                    days = days,
                    minNights = query.minNights,
                    siteTypes = siteTypes,
                    totalAtPoi = 0,
                    dates = emptyList(),
                ),
            )
            return@get
        }

        try {
            val response =
                provider.catalogAvailability(
                    CatalogAvailabilityRequest(
                        ref = ref,
                        reservables = rows.map { it.toCatalogReservableRef() },
                        start = query.start,
                        days = days,
                        minNights = query.minNights,
                        force = query.force,
                    ),
                )
            call.respondAvailabilityJson(
                PoiReservablesAvailabilityResponseSchema(
                    poiId = poiId,
                    type = type.encode(),
                    start = query.start.toString(),
                    days = days,
                    minNights = query.minNights,
                    siteTypes = siteTypes,
                    totalAtPoi = rows.size,
                    dates =
                        response.availability.toAvailableReservableDates(
                            rows = rows,
                            poiId = poiId,
                            ref = ref,
                            minNights = query.minNights,
                        ),
                ),
            )
        } catch (e: BookingProviderError) {
            val (status, error) = mapProviderError(e)
            log.info(
                "poi reservables availability poi={} provider={} failed: {}",
                poiId,
                provider.id,
                e.message,
            )
            call.respondAvailabilityJson(error, status)
        }
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
            queryParameter<Int>("days") { description = "Window length, default 30, max 60." }
            queryParameter<String>("start") { description = "YYYY-MM-DD; default is today." }
            queryParameter<Int>("min_nights") { description = "Same-site stay length, 1..31." }
            queryParameter<String>("force") { description = "Set to 1 to bypass provider cache." }
        }
        response {
            code(HttpStatusCode.OK) {
                description = "Availability for one reservable."
            }
            code(HttpStatusCode.BadRequest) {
                description = "Malformed reservable id, invalid days, or start out of range."
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

        val query = call.parseAvailabilityQuery(provider.capabilities.bookingHorizonDays)
        if (query == null) {
            call.respondAvailabilityError("bad_start", HttpStatusCode.BadRequest)
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
                        start = query.start,
                        days = days,
                        minNights = query.minNights,
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

private fun List<AvailabilityDayDto>.toAvailableReservableDates(
    rows: List<Reservable>,
    poiId: Long,
    ref: ProviderRef,
    minNights: Int,
): List<PoiReservablesAvailabilityDateSchema> {
    val rowsByRid = rows.associateBy { it.rid.encode() }
    return mapNotNull { day ->
        val availableRows =
            day.availableReservableIds
                .orEmpty()
                .mapNotNull { rowsByRid[it] }
                .distinctBy { it.id }
        if (availableRows.isEmpty()) {
            null
        } else {
            val arrival = LocalDate.parse(day.date)
            PoiReservablesAvailabilityDateSchema(
                date = day.date,
                availableCount = availableRows.size,
                total = rows.size,
                availableReservables =
                    availableRows.map { row ->
                        row.toSchema(
                            poiIds = listOf(poiId),
                            reservationUrl =
                                row.reservationUrl(
                                    ref,
                                    ReservationUrlOptions(start = arrival, minNights = minNights),
                                ),
                        )
                    },
            )
        }
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

private data class AvailabilityQuery(
    val start: LocalDate,
    val minNights: Int,
    val force: Boolean,
)

private fun ApplicationCall.parseAvailabilityQuery(bookingHorizonDays: Int): AvailabilityQuery? {
    val today = LocalDate.now(java.time.ZoneOffset.UTC)
    val start =
        when (val parsed = parseStartParam(request.queryParameters["start"], today, bookingHorizonDays)) {
            is StartParam.Ok -> parsed.value
            StartParam.Invalid -> return null
        }
    val minNights =
        request.queryParameters["min_nights"]
            ?.toIntOrNull()
            ?.coerceIn(1, MAX_MIN_NIGHTS)
            ?: 1
    val force = request.queryParameters["force"] == "1"
    return AvailabilityQuery(start = start, minNights = minNights, force = force)
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
