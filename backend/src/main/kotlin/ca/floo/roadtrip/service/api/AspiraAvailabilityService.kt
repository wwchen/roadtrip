package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.client.AspiraException
import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import ca.floo.roadtrip.models.aspira.AspiraStatus
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.repo.CachedAspiraOccupancy
import ca.floo.roadtrip.repo.CachedResult
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Provider-specific helpers for Aspira NextGen availability (Parks Canada,
// BC Provincial, WA State). The HTTP surface lives in
// AvailabilityRoutes.kt; this file just translates the cached
// AspiraAvailability payload into the shared response shape.

// Whitelist hosts to prevent SSRF. Anyone hitting the dispatch route can't
// redirect the backend at arbitrary URLs by guessing host names — the host
// is sourced from the registry/pois.source mapping, never a request param.
val ASPIRA_ALLOWED_HOSTS: Set<String> =
    setOf(
        "reservation.pc.gc.ca", // Parks Canada
        "camping.bcparks.ca", // BC Provincial
        "washington.goingtocamp.com", // WA State Parks
    )

/**
 * Fetch + classify + render the unified response for an Aspira-backed
 * campground. Throws on upstream failure — caller maps to a 503.
 *
 * The half-open window `[startDate, endDate)` is classified as independent
 * calendar days. Same-sub-area stay-length matching belongs to alert
 * execution, not public/provider availability.
 */
internal suspend fun fetchAspiraAvailabilityObservations(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
    reservableVendor: String? = null,
): AvailabilityObservationBatch {
    val days = daysBetween(startDate, endDate)
    val cached = cache.get(host, mapId, startDate, endDate.minusDays(1), force)
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cached.hit,
            ageSeconds = cached.ageSeconds,
            ttlSeconds = cached.ttlSeconds,
        )
    return AvailabilityObservationBatch(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        observations = observationsFromAspiraAvailability(cached.data, startDate, days, cached.observedAt, reservableVendor),
        cacheBlock = cacheBlock,
        host = host,
        mapId = mapId.toString(),
    )
}

/**
 * POI-scoped Aspira availability using the linked reservable catalog instead
 * of only the POI's parent map. Some Aspira parks can have one visible POI
 * map whose linked sites live under several child maps; this groups by the
 * per-reservable child map and classifies the actual resource ids.
 */
internal suspend fun fetchAspiraCatalogObservations(
    cache: CachedAspiraAvailability,
    host: String,
    parentMapId: Int,
    reservables: List<AspiraCatalogReservable>,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
): AvailabilityObservationBatch {
    val days = daysBetween(startDate, endDate)
    val targets =
        reservables
            .distinctBy { it.rid }
            .map { it.copy(mapId = it.mapId ?: parentMapId) }
    if (targets.isEmpty()) {
        return fetchAspiraAvailabilityObservations(
            cache = cache,
            host = host,
            mapId = parentMapId,
            startDate = startDate,
            endDate = endDate,
            force = force,
        )
    }

    val cachedByMap = mutableMapOf<Int, CachedResult>()
    for (mapId in targets.map { it.mapId!! }.distinct()) {
        cachedByMap[mapId] = cache.get(host, mapId, startDate, endDate.minusDays(1), force)
    }

    val resourceRows =
        targets.map { target ->
            CatalogResourceDays(
                rid = target.rid,
                days = cachedByMap[target.mapId]?.data?.byResource?.get(target.resourceId),
                observedAt = cachedByMap[target.mapId]?.observedAt ?: Instant.EPOCH,
            )
        }
    val cacheResults = cachedByMap.values
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cacheResults.all { it.hit },
            ageSeconds = cacheResults.maxOfOrNull { it.ageSeconds } ?: 0,
            ttlSeconds = cacheResults.minOfOrNull { it.ttlSeconds } ?: 0,
        )
    return AvailabilityObservationBatch(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        observations = observationsFromLinkedResourceCatalog(resourceRows, startDate, days),
        cacheBlock = cacheBlock,
        host = host,
        mapId = parentMapId.toString(),
    )
}

/**
 * Aspira's results list is driven by `/api/occupancy`, not the raw
 * `/api/availability/map` resource statuses. Query each date as a one-day
 * window so the response remains a set of independent per-day facts.
 */
internal suspend fun fetchAndClassifyAspiraCatalogOccupancy(
    cache: CachedAspiraOccupancy,
    host: String,
    parentMapId: Int,
    resourceLocationId: Int,
    reservables: List<AspiraCatalogReservable>,
    today: LocalDate,
    days: Int,
    force: Boolean,
): AvailabilityResponseDto {
    val targets =
        reservables
            .distinctBy { it.rid }
            .map { it.copy(mapId = it.mapId ?: parentMapId) }
    if (targets.isEmpty()) {
        return availabilityResponseFromObservations(
            AvailabilityObservationBatch(
                provider = "aspira",
                startDate = today,
                endDate = today.plusDays(days.toLong()),
                observations = emptyList(),
                cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
                host = host,
                mapId = parentMapId.toString(),
            ),
        )
    }

    val cachedByDate = mutableListOf<CachedOccupancyDay>()
    val observations =
        (0 until days).flatMap { offset ->
            val arrival = today.plusDays(offset.toLong())
            val checkout = arrival.plusDays(1)
            val cached = cache.get(host, resourceLocationId, arrival, checkout, force)
            cachedByDate += CachedOccupancyDay(cached.hit, cached.ageSeconds, cached.ttlSeconds)
            observationsFromOccupancyCatalogArrivalDay(targets, cached.data.resourceOccupancy, arrival, cached.observedAt)
        }
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cachedByDate.all { it.hit },
            ageSeconds = cachedByDate.maxOfOrNull { it.ageSeconds } ?: 0,
            ttlSeconds = cachedByDate.minOfOrNull { it.ttlSeconds } ?: 0,
        )
    return availabilityResponseFromObservations(
        AvailabilityObservationBatch(
            provider = "aspira",
            startDate = today,
            endDate = today.plusDays(days.toLong()),
            observations = observations,
            cacheBlock = cacheBlock,
            host = host,
            mapId = parentMapId.toString(),
        ),
    )
}

/**
 * Same cached Aspira `/api/availability/map` response as the campground
 * rollup, narrowed to one `resourceAvailabilities` key.
 */
internal suspend fun fetchAspiraResourceObservations(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    resourceId: String,
    reservableVendor: String,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
): AvailabilityObservationBatch {
    val days = daysBetween(startDate, endDate)
    val cached = cache.get(host, mapId, startDate, endDate.minusDays(1), force)
    val resourceDays = cached.data.byResource[resourceId].orEmpty()
    val reservableId = "site:$reservableVendor:$resourceId"
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cached.hit,
            ageSeconds = cached.ageSeconds,
            ttlSeconds = cached.ttlSeconds,
        )
    return AvailabilityObservationBatch(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        observations = observationsFromResourceDays(resourceDays, startDate, days, reservableId, cached.observedAt),
        cacheBlock = cacheBlock,
        host = host,
        mapId = mapId.toString(),
        reservableId = reservableId,
    )
}

/**
 * Convert Aspira's per-day status arrays into the FE's day-status shape.
 *
 * For each date D, a sub-area counts as available only when its canonical
 * status is online-bookable. Missing provider rows are explicit unknown data.
 *
 * When the park has no sub-areas (rare, but possible for a single-loop
 * park), fall back to the `mapAvailabilities` rollup as one virtual sub-area.
 */
private fun observationsFromAspiraAvailability(
    avail: AspiraAvailability,
    start: LocalDate,
    days: Int,
    observedAt: Instant,
    reservableVendor: String? = null,
): List<ReservableDayObservation> {
    if (reservableVendor != null && avail.byResource.isNotEmpty()) {
        return observationsFromResourceCatalog(avail.byResource, start, days, reservableVendor, observedAt)
    }
    val sub = avail.byMapLink.values.toList()
    val rollup = avail.parkRollup
    return if (sub.isNotEmpty()) {
        observationsFromIndexedStatusRows(sub, start, days, reservableVendor ?: "aspira", "__map_link", observedAt)
    } else {
        val id = "site:${reservableVendor ?: "aspira"}:__park__"
        (0 until days).map { d ->
            ReservableDayObservation(
                reservableId = id,
                date = start.plusDays(d.toLong()),
                observedAt = observedAt,
                status = statusAt(rollup, d),
            )
        }
    }
}

private fun observationsFromResourceDays(
    resourceDays: List<Int>,
    start: LocalDate,
    days: Int,
    reservableId: String,
    observedAt: Instant,
): List<ReservableDayObservation> =
    (0 until days).map { d ->
        ReservableDayObservation(
            reservableId = reservableId,
            date = start.plusDays(d.toLong()),
            observedAt = observedAt,
            status = statusAt(resourceDays, d),
        )
    }

private fun observationsFromResourceCatalog(
    byResource: Map<String, List<Int>>,
    start: LocalDate,
    days: Int,
    reservableVendor: String,
    observedAt: Instant,
): List<ReservableDayObservation> =
    byResource.flatMap { (resourceId, resourceDays) ->
        observationsFromResourceDays(
            resourceDays = resourceDays,
            start = start,
            days = days,
            reservableId = "site:$reservableVendor:$resourceId",
            observedAt = observedAt,
        )
    }

private fun observationsFromLinkedResourceCatalog(
    resources: List<CatalogResourceDays>,
    start: LocalDate,
    days: Int,
): List<ReservableDayObservation> =
    resources.flatMap { resource ->
        (0 until days).map { d ->
            ReservableDayObservation(
                reservableId = resource.rid,
                date = start.plusDays(d.toLong()),
                observedAt = resource.observedAt,
                status = resource.days?.let { statusAt(it, d) } ?: AvailabilityStatus.UNKNOWN,
            )
        }
    }

private fun observationsFromOccupancyCatalogArrivalDay(
    resources: List<AspiraCatalogReservable>,
    occupancyRows: List<ca.floo.roadtrip.client.AspiraResourceOccupancy>,
    arrival: LocalDate,
    observedAt: Instant,
): List<ReservableDayObservation> {
    val occupancyByResourceId = occupancyRows.associateBy { it.resourceId.toString() }
    return resources.map { resource ->
        val occupancy = occupancyByResourceId[resource.resourceId]
        val status =
            when {
                occupancy == null -> AvailabilityStatus.UNKNOWN
                occupancy.availability == ASPIRA_OCCUPANCY_AVAILABLE && !occupancy.filtered -> AvailabilityStatus.AVAILABLE
                else -> AvailabilityStatus.RESERVED
            }
        ReservableDayObservation(
            reservableId = resource.rid,
            date = arrival,
            observedAt = observedAt,
            status = status,
        )
    }
}

internal data class AspiraCatalogReservable(
    val rid: String,
    val resourceId: String,
    val mapId: Int?,
    val resourceLocationId: Int? = null,
)

private data class CatalogResourceDays(
    val rid: String,
    val days: List<Int>?,
    val observedAt: Instant,
)

private data class CachedOccupancyDay(
    val hit: Boolean,
    val ageSeconds: Long,
    val ttlSeconds: Long,
)

private const val ASPIRA_OCCUPANCY_AVAILABLE = 0

private fun observationsFromIndexedStatusRows(
    rows: List<List<Int>>,
    start: LocalDate,
    days: Int,
    reservableVendor: String,
    idPrefix: String,
    observedAt: Instant,
): List<ReservableDayObservation> =
    rows.flatMapIndexed { index, statuses ->
        (0 until days).map { d ->
            ReservableDayObservation(
                reservableId = "site:$reservableVendor:$idPrefix:$index",
                date = start.plusDays(d.toLong()),
                observedAt = observedAt,
                status = statusAt(statuses, d),
            )
        }
    }

private fun statusAt(
    statuses: List<Int>,
    offset: Int,
): AvailabilityStatus =
    if (offset < statuses.size) {
        AspiraStatus.classify(statuses[offset])
    } else {
        AvailabilityStatus.UNKNOWN
    }

private fun daysBetween(
    startDate: LocalDate,
    endDate: LocalDate,
): Int = ChronoUnit.DAYS.between(startDate, endDate).toInt()

internal fun mapAspiraUpstreamError(e: AspiraException): Pair<HttpStatusCode, AvailabilityErrorSchema> {
    val status = e.httpStatus
    return when {
        status == 429 ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("rate_limited", retryAfterS = 60)
        status == 503 || (e.message?.contains("WAF") == true) ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_blocked", retryAfterS = 300)
        else ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_5xx", retryAfterS = 30)
    }
}
