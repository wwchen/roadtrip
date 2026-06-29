package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraException
import ca.floo.roadtrip.models.api.AvailabilityErrorDto
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.ReservableDayObservation
import ca.floo.roadtrip.models.metadata.aspira.AspiraStatus
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Provider-specific helpers for Aspira NextGen availability (Parks Canada,
// BC Provincial, WA State). The HTTP surface lives in
// AvailabilityRoutes.kt; this file just translates the AspiraAvailability
// payload into the shared response shape.

/**
 * Fetch + classify + render the unified response for an Aspira-backed
 * campground. Throws on upstream failure — caller maps to a 503.
 *
 * The half-open window `[startDate, endDate)` is classified as independent
 * calendar days. Same-sub-area stay-length matching belongs to alert
 * execution, not public/provider availability.
 */
internal suspend fun fetchAspiraAvailabilityObservations(
    client: AspiraAvailabilityClient,
    host: String,
    mapId: Int,
    startDate: LocalDate,
    endDate: LocalDate,
    reservableVendor: String? = null,
): AvailabilityObservationBatch {
    val days = daysBetween(startDate, endDate)
    val observedAt = Instant.now()
    val data = client.fetch(host, mapId, startDate, endDate.minusDays(1))
    return AvailabilityObservationBatch(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        observations = observationsFromAspiraAvailability(data, startDate, days, observedAt, reservableVendor),
        cacheBlock = directFetchCacheBlock(),
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
    client: AspiraAvailabilityClient,
    host: String,
    parentMapId: Int,
    reservables: List<AspiraCatalogReservable>,
    startDate: LocalDate,
    endDate: LocalDate,
): AvailabilityObservationBatch {
    val days = daysBetween(startDate, endDate)
    val targets =
        reservables
            .distinctBy { it.rid }
            .map { it.copy(mapId = it.mapId ?: parentMapId) }
    if (targets.isEmpty()) {
        return fetchAspiraAvailabilityObservations(
            client = client,
            host = host,
            mapId = parentMapId,
            startDate = startDate,
            endDate = endDate,
        )
    }

    val observedAt = Instant.now()
    val dataByMap = mutableMapOf<Int, AspiraAvailability>()
    for (mapId in targets.map { it.mapId!! }.distinct()) {
        dataByMap[mapId] = client.fetch(host, mapId, startDate, endDate.minusDays(1))
    }

    val resourceRows =
        targets.map { target ->
            CatalogResourceDays(
                rid = target.rid,
                days = dataByMap[target.mapId]?.byResource?.get(target.resourceId),
                observedAt = observedAt,
            )
        }
    return AvailabilityObservationBatch(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        observations = observationsFromLinkedResourceCatalog(resourceRows, startDate, days),
        cacheBlock = directFetchCacheBlock(),
        host = host,
        mapId = parentMapId.toString(),
    )
}

internal suspend fun fetchAspiraCatalogOccupancyObservations(
    client: AspiraAvailabilityClient,
    host: String,
    parentMapId: Int,
    resourceLocationId: Int,
    reservables: List<AspiraCatalogReservable>,
    today: LocalDate,
    days: Int,
): AvailabilityObservationBatch {
    val targets =
        reservables
            .distinctBy { it.rid }
            .map { it.copy(mapId = it.mapId ?: parentMapId) }
    if (targets.isEmpty()) {
        return AvailabilityObservationBatch(
            provider = "aspira",
            startDate = today,
            endDate = today.plusDays(days.toLong()),
            observations = emptyList(),
            cacheBlock = directFetchCacheBlock(),
            host = host,
            mapId = parentMapId.toString(),
        )
    }

    val observations =
        (0 until days).flatMap { offset ->
            val arrival = today.plusDays(offset.toLong())
            val checkout = arrival.plusDays(1)
            val data = client.fetchOccupancy(host, resourceLocationId, arrival, checkout)
            observationsFromOccupancyCatalogArrivalDay(targets, data.resourceOccupancy, arrival, Instant.now())
        }
    return AvailabilityObservationBatch(
        provider = "aspira",
        startDate = today,
        endDate = today.plusDays(days.toLong()),
        observations = observations,
        cacheBlock = directFetchCacheBlock(),
        host = host,
        mapId = parentMapId.toString(),
    )
}

/**
 * Same Aspira `/api/availability/map` response as the campground rollup,
 * narrowed to one `resourceAvailabilities` key.
 */
internal suspend fun fetchAspiraResourceObservations(
    client: AspiraAvailabilityClient,
    host: String,
    mapId: Int,
    resourceId: String,
    reservableVendor: String,
    startDate: LocalDate,
    endDate: LocalDate,
): AvailabilityObservationBatch {
    val days = daysBetween(startDate, endDate)
    val observedAt = Instant.now()
    val data = client.fetch(host, mapId, startDate, endDate.minusDays(1))
    val resourceDays = data.byResource[resourceId].orEmpty()
    val reservableId = "site:$reservableVendor:$resourceId"
    return AvailabilityObservationBatch(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        observations = observationsFromResourceDays(resourceDays, startDate, days, reservableId, observedAt),
        cacheBlock = directFetchCacheBlock(),
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
    occupancyRows: List<ca.floo.roadtrip.clients.aspira.AspiraResourceOccupancy>,
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

private fun directFetchCacheBlock(): AvailabilityCacheBlock =
    AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L)

internal fun mapAspiraUpstreamError(e: AspiraException): Pair<HttpStatusCode, AvailabilityErrorDto> {
    val status = e.httpStatus
    return when {
        status == 429 ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("rate_limited", upstreamStatus = status)
        status == 503 || (e.message?.contains("WAF") == true) ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_blocked", upstreamStatus = status)
        else ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_5xx", upstreamStatus = status)
    }
}
