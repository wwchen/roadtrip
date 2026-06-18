package ca.floo.roadtrip.routes

import ca.floo.roadtrip.clients.aspira.AspiraException
import ca.floo.roadtrip.config.ApiCacheEntity
import ca.floo.roadtrip.models.api.ApiErrorSchema
import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import ca.floo.roadtrip.models.api.BulkAvailEntrySchema
import ca.floo.roadtrip.models.api.BulkAvailRequestSchema
import ca.floo.roadtrip.models.api.BulkAvailResponseSchema
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.models.domain.ReservableId
import ca.floo.roadtrip.models.domain.ReservableType
import ca.floo.roadtrip.repo.AvailabilitySnapshotRepo
import ca.floo.roadtrip.repo.CampsiteProviderRefRow
import ca.floo.roadtrip.repo.CampsiteProviderRepo
import ca.floo.roadtrip.repo.ReservableRepo
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.PoiReservablesAvailabilityResponseDto
import ca.floo.roadtrip.service.api.SnapshotBackedAvailabilityService
import ca.floo.roadtrip.service.api.availabilityDatesFromObservations
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.service.api.availabilityResponseFromObservations
import ca.floo.roadtrip.service.api.encodeAvailabilityJson
import ca.floo.roadtrip.service.reservation.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ProviderRefParser
import ca.floo.roadtrip.service.reservation.ReservableAvailabilityRequest
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.slf4j.LoggerFactory
import java.time.Duration
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

/**
 * Unified availability endpoints. Dispatch to the upstream is the registry's
 * job; this route parses inputs, looks up the right [ReservationProvider], and
 * serializes the result.
 *
 * See [ReservationProviderRegistry] / `docs/reservation-providers.md` for the
 * provider-port architecture. Adding a new upstream is one new adapter file
 * + one registry wiring line; this file does not change.
 */
fun Route.availabilityRoutes(
    providerRefs: CampsiteProviderRepo,
    reservationProviders: ReservationProviderRegistry,
    reservables: ReservableRepo,
    snapshots: AvailabilitySnapshotRepo? = null,
    snapshotFreshnessTtl: (ReservationProviderId) -> Duration = ::defaultSnapshotFreshnessTtl,
) {
    val rateLimit = IpRateLimiter(perMinute = IP_RATE_LIMIT_PER_MINUTE)
    val snapshotAvailability = SnapshotBackedAvailabilityService(snapshots)

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
            queryParameter<String>("start_date") { description = "YYYY-MM-DD; default is today UTC." }
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
                body<AvailabilityErrorSchema> { mediaTypes(ContentType.Application.Json) }
            }
            code(HttpStatusCode.NotFound) {
                description = "No active POI with that id."
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

        val ip = call.request.origin.remoteHost
        if (!rateLimit.allow(ip)) {
            call.respondAvailabilityError(
                "ip_throttled",
                HttpStatusCode.ServiceUnavailable,
            )
            return@get
        }

        val row = providerRefs.findProviderRef(poiId)
        val provider = row?.let { reservationProviders.forPoi(it) }
        val parentRef = row?.providerRefJson?.let { ProviderRefParser.parse(it) }
        if (row == null || provider == null || parentRef == null) {
            // No usable provider_ref. Either the POI has no online reservations
            // (walk-up / non-bookable), the source has no adapter wired, or the
            // ref is unparseable. Distinguish "POI doesn't exist" (404) from
            // "POI exists but isn't bookable" (empty array) so the FE can
            // hide the matrix uniformly.
            if (!providerRefs.campgroundExists(poiId)) {
                return@get call.respondAvailabilityError("not_found", HttpStatusCode.NotFound)
            }
            call.respondAvailabilityJson(emptyPoiReservablesAvailability(poiId))
            return@get
        }

        val query = call.parseAvailabilityWindow(provider.capabilities.bookingHorizonDays)
        if (query == null) {
            call.respondAvailabilityError("bad_date_window", HttpStatusCode.BadRequest)
            return@get
        }

        val siteTypes = call.queryValues("site_type", "siteType")
        val catalogRows =
            reservables
                .findByPoi(poiId, ReservableType.SITE)
                .filterBySiteTypes(siteTypes)

        if (catalogRows.isEmpty()) {
            call.respondAvailabilityJson(
                PoiReservablesAvailabilityResponseDto(
                    poiId = poiId,
                    startDate = query.startDate.toString(),
                    endDate = query.endDate.toString(),
                    reservables = emptyList(),
                ),
            )
            return@get
        }

        try {
            val perReservable =
                coroutineScope {
                    catalogRows
                        .map { reservable ->
                            async {
                                fetchReservableAvailability(
                                    reservable = reservable,
                                    parentRef = parentRef,
                                    provider = provider,
                                    snapshotAvailability = snapshotAvailability,
                                    snapshotFreshnessTtl = snapshotFreshnessTtl,
                                    startDate = query.startDate,
                                    endDate = query.endDate,
                                    force = query.force,
                                )
                            }
                        }.awaitAll()
                }

            call.respondAvailabilityJson(
                PoiReservablesAvailabilityResponseDto(
                    poiId = poiId,
                    startDate = query.startDate.toString(),
                    endDate = query.endDate.toString(),
                    reservables = perReservable,
                ),
            )
        } catch (e: ReservationProviderError) {
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
            "campground POI, dispatches to its ReservationProvider, and returns " +
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
            )
            return@get
        }

        val poiIds = reservables.poiIdsForReservable(row.id)
        val rowsById = providerRefs.findProviderRefs(poiIds)
        val parent =
            poiIds
                .asSequence()
                .mapNotNull { rowsById[it] }
                .firstOrNull { reservationProviders.forPoi(it) != null && ProviderRefParser.parse(it.providerRefJson) != null }
                ?: return@get call.respondAvailabilityError("unknown_campground", HttpStatusCode.NotFound)
        val provider = reservationProviders.forPoi(parent)!!
        val parentRef = ProviderRefParser.parse(parent.providerRefJson)!!

        val query = call.parseAvailabilityWindow(provider.capabilities.bookingHorizonDays)
        if (query == null) {
            call.respondAvailabilityError("bad_date_window", HttpStatusCode.BadRequest)
            return@get
        }

        try {
            val response =
                fetchReservableAvailability(
                    reservable = row,
                    parentRef = parentRef,
                    provider = provider,
                    snapshotAvailability = snapshotAvailability,
                    snapshotFreshnessTtl = snapshotFreshnessTtl,
                    startDate = query.startDate,
                    endDate = query.endDate,
                    force = query.force,
                )
            call.respondAvailabilityJson(response)
        } catch (e: ReservationProviderError) {
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
            )
            return@post
        }

        val rowsById = providerRefs.findProviderRefs(req.ids)

        val results =
            coroutineScope {
                req.ids
                    .map { id ->
                        async {
                            fetchOneBulk(
                                poiId = id,
                                row = rowsById[id],
                                reservationProviders = reservationProviders,
                                reservables = reservables,
                                snapshotAvailability = snapshotAvailability,
                                snapshotFreshnessTtl = snapshotFreshnessTtl,
                                startDate = start,
                                endDate = end,
                            )
                        }
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

private fun Reservable.toAvailabilityTarget(): SnapshotBackedAvailabilityService.TargetReservable =
    SnapshotBackedAvailabilityService.TargetReservable(
        dbId = id,
        rid = rid.encode(),
    )

private fun availabilityMetadata(
    providerId: ReservationProviderId,
    ref: ProviderRef,
    reservableId: String? = null,
): SnapshotBackedAvailabilityService.Metadata =
    SnapshotBackedAvailabilityService.Metadata(
        provider = providerId.name.lowercase(),
        campgroundId = (ref as? ProviderRef.RecGov)?.recgovId,
        mapId = (ref as? ProviderRef.Aspira)?.mapId?.toString(),
        reservableId = reservableId,
    )

private fun defaultSnapshotFreshnessTtl(providerId: ReservationProviderId): Duration =
    when (providerId) {
        ReservationProviderId.RECGOV -> ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl
        ReservationProviderId.ASPIRA -> ApiCacheEntity.ASPIRA_AVAILABILITY.defaultTtl
        ReservationProviderId.CAMIS -> ApiCacheEntity.RECGOV_AVAILABILITY.defaultTtl
    }

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

// POI exists but has no reservation provider wired (or its provider_ref is
// unparseable). The bookable-state gate would normally hide the matrix anyway;
// returning an empty wrapper keeps the response shape uniform with the
// "POI has zero linked reservables" case so the FE has one branch to handle.
//
// Echoes the requested window (or today/+7) so the FE can render a placeholder
// grid. The dates carry no real meaning when reservables is empty.
private const val EMPTY_WINDOW_DEFAULT_DAYS: Long = 7

private fun emptyPoiReservablesAvailability(poiId: Long): PoiReservablesAvailabilityResponseDto {
    val today = LocalDate.now(ZoneOffset.UTC)
    return PoiReservablesAvailabilityResponseDto(
        poiId = poiId,
        startDate = today.toString(),
        endDate = today.plusDays(EMPTY_WINDOW_DEFAULT_DAYS).toString(),
        reservables = emptyList(),
    )
}

private suspend fun fetchReservableAvailability(
    reservable: Reservable,
    parentRef: ProviderRef,
    provider: ReservationProvider,
    snapshotAvailability: SnapshotBackedAvailabilityService,
    snapshotFreshnessTtl: (ReservationProviderId) -> Duration,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
): AvailabilityResponseDto {
    val ref = reservable.providerRefForReservable(parentRef)
    val rid = reservable.rid.encode()
    val batch =
        snapshotAvailability.loadOrFetch(
            SnapshotBackedAvailabilityService.Request(
                metadata = availabilityMetadata(provider.id, ref, reservableId = rid),
                targets = listOf(reservable.toAvailabilityTarget()),
                startDate = startDate,
                endDate = endDate,
                ttl = snapshotFreshnessTtl(provider.id),
                force = force,
            ),
        ) {
            provider.reservableAvailability(
                ReservableAvailabilityRequest(
                    ref = ref,
                    vendorId = reservable.rid.vendorId,
                    startDate = startDate,
                    endDate = endDate,
                    force = force,
                ),
            )
        }
    return availabilityResponseFromObservations(batch)
}

private suspend fun fetchOneBulk(
    poiId: Long,
    row: CampsiteProviderRefRow?,
    reservationProviders: ReservationProviderRegistry,
    reservables: ReservableRepo,
    snapshotAvailability: SnapshotBackedAvailabilityService,
    snapshotFreshnessTtl: (ReservationProviderId) -> Duration,
    startDate: LocalDate,
    endDate: LocalDate,
): BulkAvailEntrySchema {
    if (row == null) {
        return BulkAvailEntrySchema(id = poiId, status = 404, available_dates = emptyList())
    }
    val provider =
        reservationProviders.forPoi(row)
            ?: return BulkAvailEntrySchema(id = poiId, status = 422, available_dates = emptyList())
    val ref =
        ProviderRefParser.parse(row.providerRefJson)
            ?: return BulkAvailEntrySchema(id = poiId, status = 422, available_dates = emptyList())

    return try {
        val catalogRows = reservables.findByPoi(poiId, ReservableType.SITE)
        val catalogRefs = catalogRows.map { it.toCatalogReservableRef() }
        val batch =
            snapshotAvailability.loadOrFetch(
                SnapshotBackedAvailabilityService.Request(
                    metadata = availabilityMetadata(provider.id, ref),
                    targets = catalogRows.map { it.toAvailabilityTarget() },
                    startDate = startDate,
                    endDate = endDate,
                    ttl = snapshotFreshnessTtl(provider.id),
                    force = false,
                ),
            ) {
                provider.catalogAvailability(
                    CatalogAvailabilityRequest(
                        ref = ref,
                        reservables = catalogRefs,
                        startDate = startDate,
                        endDate = endDate,
                        force = false,
                    ),
                )
            }
        val dates = availabilityDatesFromObservations(batch)
        BulkAvailEntrySchema(id = poiId, status = 200, available_dates = dates)
    } catch (e: ReservationProviderError) {
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
internal fun mapProviderError(e: ReservationProviderError): Pair<HttpStatusCode, AvailabilityErrorSchema> {
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

/** Numeric status for the bulk endpoint's per-id `status` field. */
private fun httpStatusFor(e: ReservationProviderError): Int =
    when (e) {
        is ReservationProviderError.RateLimited -> 429
        is ReservationProviderError.Unsupported -> 422
        is ReservationProviderError.WrongRefType -> 500
        else -> 503
    }

private suspend fun ApplicationCall.respondAvailabilityError(
    error: String,
    status: HttpStatusCode,
) {
    respondAvailabilityJson(availabilityErrorDto(error), status)
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
