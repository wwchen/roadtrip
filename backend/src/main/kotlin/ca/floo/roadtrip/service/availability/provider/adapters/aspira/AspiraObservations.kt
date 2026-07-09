package ca.floo.roadtrip.service.availability.provider.adapters.aspira

import ca.floo.roadtrip.clients.aspira.AspiraAvailability
import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraException
import ca.floo.roadtrip.models.api.AvailabilityErrorDto
import ca.floo.roadtrip.models.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.availability.AvailabilityStatus
import ca.floo.roadtrip.models.availability.CampsiteDayObservation
import ca.floo.roadtrip.models.metadata.aspira.AspiraResourceAvailability
import ca.floo.roadtrip.models.metadata.aspira.AspiraStatus
import ca.floo.roadtrip.service.api.availabilityErrorDto
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
    campsiteVendor: String? = null,
): AvailabilityObservationBatch {
    val days = daysBetween(startDate, endDate)
    val observedAt = Instant.now()
    val data = client.fetch(host, mapId, startDate, endDate.minusDays(1))
    return AvailabilityObservationBatch(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        observations = observationsFromAspiraAvailability(data, startDate, days, observedAt, campsiteVendor),
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
    campsites: List<AspiraCatalogCampsite>,
    startDate: LocalDate,
    endDate: LocalDate,
): AvailabilityObservationBatch {
    val days = daysBetween(startDate, endDate)
    val targets =
        campsites
            .distinctBy { it.campsiteId }
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
                campsiteId = target.campsiteId,
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
    campsites: List<AspiraCatalogCampsite>,
    today: LocalDate,
    days: Int,
): AvailabilityObservationBatch {
    val targets =
        campsites
            .distinctBy { it.campsiteId }
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
    campsiteVendor: String? = null,
): List<CampsiteDayObservation> {
    if (campsiteVendor != null && avail.byResource.isNotEmpty()) {
        return observationsFromResourceCatalog(avail.byResource, start, days, campsiteVendor, observedAt)
    }
    val sub = avail.byMapLink.values.toList()
    val rollup = avail.parkRollup
    return if (sub.isNotEmpty()) {
        observationsFromIndexedStatusRows(sub, start, days, campsiteVendor ?: "aspira", "__map_link", observedAt)
    } else {
        (0 until days).map { d ->
            CampsiteDayObservation(
                campsiteId = null,
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
    campsiteId: Long?,
    observedAt: Instant,
): List<CampsiteDayObservation> =
    (0 until days).map { d ->
        CampsiteDayObservation(
            campsiteId = campsiteId,
            date = start.plusDays(d.toLong()),
            observedAt = observedAt,
            status = resourceStatusAt(resourceDays, d),
        )
    }

private fun observationsFromResourceCatalog(
    byResource: Map<String, List<Int>>,
    start: LocalDate,
    days: Int,
    campsiteVendor: String,
    observedAt: Instant,
): List<CampsiteDayObservation> =
    byResource.flatMap { (resourceId, resourceDays) ->
        observationsFromResourceDays(
            resourceDays = resourceDays,
            start = start,
            days = days,
            campsiteId = null,
            observedAt = observedAt,
        )
    }

private fun observationsFromLinkedResourceCatalog(
    resources: List<CatalogResourceDays>,
    start: LocalDate,
    days: Int,
): List<CampsiteDayObservation> =
    resources.flatMap { resource ->
        (0 until days).map { d ->
            CampsiteDayObservation(
                campsiteId = resource.campsiteId,
                date = start.plusDays(d.toLong()),
                observedAt = resource.observedAt,
                status = resource.days?.let { resourceStatusAt(it, d) } ?: AvailabilityStatus.UNKNOWN,
            )
        }
    }

private fun observationsFromOccupancyCatalogArrivalDay(
    resources: List<AspiraCatalogCampsite>,
    occupancyRows: List<ca.floo.roadtrip.clients.aspira.AspiraResourceOccupancy>,
    arrival: LocalDate,
    observedAt: Instant,
): List<CampsiteDayObservation> {
    val occupancyByResourceId = occupancyRows.associateBy { it.resourceId.toString() }
    return resources.map { resource ->
        val occupancy = occupancyByResourceId[resource.resourceId]
        val status =
            when {
                occupancy == null -> AvailabilityStatus.UNKNOWN
                occupancy.filtered -> AvailabilityStatus.RESERVED
                else -> AspiraResourceAvailability.classify(occupancy.availability)
            }
        CampsiteDayObservation(
            campsiteId = resource.campsiteId,
            date = arrival,
            observedAt = observedAt,
            status = status,
        )
    }
}

internal data class AspiraCatalogCampsite(
    val campsiteId: Long,
    val resourceId: String,
    val mapId: Int?,
    val resourceLocationId: Int? = null,
)

private data class CatalogResourceDays(
    val campsiteId: Long,
    val days: List<Int>?,
    val observedAt: Instant,
)

private fun observationsFromIndexedStatusRows(
    rows: List<List<Int>>,
    start: LocalDate,
    days: Int,
    campsiteVendor: String,
    idPrefix: String,
    observedAt: Instant,
): List<CampsiteDayObservation> =
    rows.flatMapIndexed { index, statuses ->
        (0 until days).map { d ->
            CampsiteDayObservation(
                campsiteId = null,
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

private fun resourceStatusAt(
    statuses: List<Int>,
    offset: Int,
): AvailabilityStatus =
    if (offset < statuses.size) {
        AspiraResourceAvailability.classify(statuses[offset])
    } else {
        AvailabilityStatus.UNKNOWN
    }

private fun daysBetween(
    startDate: LocalDate,
    endDate: LocalDate,
): Int = ChronoUnit.DAYS.between(startDate, endDate).toInt()

private fun directFetchCacheBlock(): AvailabilityCacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L)

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
