package ca.floo.roadtrip.service.api

import ca.floo.roadtrip.client.AspiraAvailability
import ca.floo.roadtrip.client.AspiraException
import ca.floo.roadtrip.models.api.AvailabilityErrorSchema
import ca.floo.roadtrip.models.aspira.AspiraStatus
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.repo.CachedAspiraOccupancy
import ca.floo.roadtrip.repo.CachedResult
import io.ktor.http.HttpStatusCode
import java.time.LocalDate

// Provider-specific helpers for Aspira NextGen availability (Parks Canada,
// BC Provincial, WA State). The HTTP surface lives in
// CampsiteAvailabilityRoutes.kt; this file just translates the cached
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
internal suspend fun fetchAndClassifyAspira(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
    reservableVendor: String? = null,
): AvailabilityResponseDto {
    val days =
        java.time.temporal.ChronoUnit.DAYS
            .between(startDate, endDate)
            .toInt()
    val cached = cache.get(host, mapId, startDate, endDate.minusDays(1), force)
    val perDay = classifyDays(cached.data, startDate, days, reservableVendor)
    val state = classifyWindowState(perDay)
    val summary = summarizeWindow(days, perDay, state)
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cached.hit,
            ageSeconds = cached.ageSeconds,
            ttlSeconds = cached.ttlSeconds,
        )
    return availabilityResponseDto(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        perDay = perDay,
        state = state,
        summary = summary,
        seasonBlock = null, // Aspira doesn't expose reopen-date hints
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
internal suspend fun fetchAndClassifyAspiraCatalog(
    cache: CachedAspiraAvailability,
    host: String,
    parentMapId: Int,
    reservables: List<AspiraCatalogReservable>,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
): AvailabilityResponseDto {
    val days =
        java.time.temporal.ChronoUnit.DAYS
            .between(startDate, endDate)
            .toInt()
    val targets =
        reservables
            .distinctBy { it.rid }
            .map { it.copy(mapId = it.mapId ?: parentMapId) }
    if (targets.isEmpty()) {
        return fetchAndClassifyAspira(
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
            )
        }
    val perDay = classifyLinkedResourceCatalogDays(resourceRows, startDate, days)
    val state = classifyWindowState(perDay)
    val summary = summarizeWindow(days, perDay, state)
    val cacheResults = cachedByMap.values
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cacheResults.all { it.hit },
            ageSeconds = cacheResults.maxOfOrNull { it.ageSeconds } ?: 0,
            ttlSeconds = cacheResults.minOfOrNull { it.ttlSeconds } ?: 0,
        )
    return availabilityResponseDto(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        perDay = perDay,
        state = state,
        summary = summary,
        seasonBlock = null,
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
        return availabilityResponseDto(
            provider = "aspira",
            startDate = today,
            endDate = today.plusDays(days.toLong()),
            perDay = emptyList(),
            state = "success",
            summary = "No availability",
            seasonBlock = null,
            cacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0, ttlSeconds = 0),
            host = host,
            mapId = parentMapId.toString(),
        )
    }

    val cachedByDate = mutableListOf<CachedOccupancyDay>()
    val perDay =
        (0 until days).map { offset ->
            val arrival = today.plusDays(offset.toLong())
            val checkout = arrival.plusDays(1)
            val cached = cache.get(host, resourceLocationId, arrival, checkout, force)
            cachedByDate += CachedOccupancyDay(cached.hit, cached.ageSeconds, cached.ttlSeconds)
            classifyOccupancyCatalogArrivalDay(targets, cached.data.resourceOccupancy, arrival)
        }
    val state = classifyWindowState(perDay)
    val summary = summarizeWindow(days, perDay, state)
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cachedByDate.all { it.hit },
            ageSeconds = cachedByDate.maxOfOrNull { it.ageSeconds } ?: 0,
            ttlSeconds = cachedByDate.minOfOrNull { it.ttlSeconds } ?: 0,
        )
    return availabilityResponseDto(
        provider = "aspira",
        startDate = today,
        endDate = today.plusDays(days.toLong()),
        perDay = perDay,
        state = state,
        summary = summary,
        seasonBlock = null,
        cacheBlock = cacheBlock,
        host = host,
        mapId = parentMapId.toString(),
    )
}

/**
 * Same cached Aspira `/api/availability/map` response as the campground
 * rollup, narrowed to one `resourceAvailabilities` key.
 */
internal suspend fun fetchAndClassifyAspiraResource(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    resourceId: String,
    reservableVendor: String,
    startDate: LocalDate,
    endDate: LocalDate,
    force: Boolean,
): AvailabilityResponseDto {
    val days =
        java.time.temporal.ChronoUnit.DAYS
            .between(startDate, endDate)
            .toInt()
    val cached = cache.get(host, mapId, startDate, endDate.minusDays(1), force)
    val resourceDays = cached.data.byResource[resourceId].orEmpty()
    val reservableId = "site:$reservableVendor:$resourceId"
    val perDay = classifyResourceDays(resourceDays, startDate, days, reservableId)
    val state = classifyWindowState(perDay)
    val summary = summarizeWindow(days, perDay, state)
    val cacheBlock =
        AvailabilityCacheBlock(
            hit = cached.hit,
            ageSeconds = cached.ageSeconds,
            ttlSeconds = cached.ttlSeconds,
        )
    return availabilityResponseDto(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        perDay = perDay,
        state = state,
        summary = summary,
        seasonBlock = null,
        cacheBlock = cacheBlock,
        host = host,
        mapId = mapId.toString(),
        reservableId = reservableId,
    )
}

/**
 * Bulk variant: dates in `[startDate, endDate)` where at least one sub-area
 * is available that day.
 */
suspend fun availableDatesAspira(
    cache: CachedAspiraAvailability,
    host: String,
    mapId: Int,
    startDate: LocalDate,
    endDate: LocalDate,
): List<String> {
    val days =
        java.time.temporal.ChronoUnit.DAYS
            .between(startDate, endDate)
            .toInt()
    val cached = cache.get(host, mapId, startDate, endDate.minusDays(1), force = false)
    val perDay = classifyDays(cached.data, startDate, days)
    return perDay
        .filter { it.availableCount > 0 }
        .map { it.date }
}

/**
 * Convert Aspira's per-day status arrays into the FE's day-status shape.
 *
 * For each date D, a sub-area counts as available when its status for D is
 * AVAILABLE or PARTIAL. `total` is the number of sub-areas with any status on
 * that date.
 *
 * When the park has no sub-areas (rare, but possible for a single-loop
 * park), fall back to the `mapAvailabilities` rollup as one virtual sub-area.
 */
private fun classifyDays(
    avail: AspiraAvailability,
    start: LocalDate,
    days: Int,
    reservableVendor: String? = null,
): List<DayClassification> {
    if (reservableVendor != null && avail.byResource.isNotEmpty()) {
        return classifyResourceCatalogDays(avail.byResource, start, days, reservableVendor)
    }
    val sub = avail.byMapLink.values.toList()
    val rollup = avail.parkRollup
    return (0 until days).map { d ->
        val date = start.plusDays(d.toLong()).toString()
        if (sub.isNotEmpty()) {
            classifyArrivalDay(sub, d, date)
        } else {
            classifyArrivalDay(listOf(rollup), d, date)
        }
    }
}

private fun classifyResourceDays(
    resourceDays: List<Int>,
    start: LocalDate,
    days: Int,
    reservableId: String? = null,
): List<DayClassification> =
    (0 until days).map { d ->
        val date = start.plusDays(d.toLong()).toString()
        if (d >= resourceDays.size) {
            DayClassification(
                date = date,
                status = "closed",
                availableCount = 0,
                total = 0,
                availableReservableIds = knownEmptyReservableIds(reservableId),
            )
        } else {
            classifyResourceArrivalDay(resourceDays, d, date, reservableId)
        }
    }

private fun classifyResourceCatalogDays(
    byResource: Map<String, List<Int>>,
    start: LocalDate,
    days: Int,
    reservableVendor: String,
): List<DayClassification> =
    (0 until days).map { d ->
        val date = start.plusDays(d.toLong()).toString()
        classifyResourceCatalogArrivalDay(byResource, d, date, reservableVendor)
    }

private fun classifyLinkedResourceCatalogDays(
    resources: List<CatalogResourceDays>,
    start: LocalDate,
    days: Int,
): List<DayClassification> =
    (0 until days).map { d ->
        val date = start.plusDays(d.toLong()).toString()
        classifyLinkedResourceCatalogArrivalDay(resources, d, date)
    }

private fun classifyResourceCatalogArrivalDay(
    byResource: Map<String, List<Int>>,
    d: Int,
    date: String,
    reservableVendor: String,
): DayClassification {
    var available = 0
    var booked = 0
    var closed = 0
    val availableReservableIds = mutableListOf<String>()
    for ((resourceId, resourceDays) in byResource) {
        if (d >= resourceDays.size) continue
        val arrivalCls = AspiraStatus.classify(resourceDays[d])
        when (arrivalCls) {
            "closed" -> closed++
            "available", "partial" -> {
                available++
                availableReservableIds += "site:$reservableVendor:$resourceId"
            }
            else -> booked++
        }
    }
    val total = available + booked + closed
    val status =
        when {
            total == 0 -> "closed"
            closed == total -> "closed"
            available > 0 -> "available"
            else -> "booked"
        }
    return DayClassification(date, status, available, total, availableReservableIds.sorted())
}

private fun classifyLinkedResourceCatalogArrivalDay(
    resources: List<CatalogResourceDays>,
    d: Int,
    date: String,
): DayClassification {
    var available = 0
    var booked = 0
    var closed = 0
    val availableReservableIds = mutableListOf<String>()
    for (resource in resources) {
        val days = resource.days
        if (days == null || d >= days.size) {
            closed++
            continue
        }
        val arrivalCls = AspiraStatus.classify(days[d])
        when (arrivalCls) {
            "closed" -> closed++
            "available", "partial" -> {
                available++
                availableReservableIds += resource.rid
            }
            else -> booked++
        }
    }
    val total = available + booked + closed
    val status =
        when {
            total == 0 -> "closed"
            closed == total -> "closed"
            available > 0 -> "available"
            else -> "booked"
        }
    return DayClassification(date, status, available, total, availableReservableIds.sorted())
}

private fun classifyOccupancyCatalogArrivalDay(
    resources: List<AspiraCatalogReservable>,
    occupancyRows: List<ca.floo.roadtrip.client.AspiraResourceOccupancy>,
    arrival: LocalDate,
): DayClassification {
    val occupancyByResourceId = occupancyRows.associateBy { it.resourceId.toString() }
    val availableReservableIds = mutableListOf<String>()
    for (resource in resources) {
        val occupancy = occupancyByResourceId[resource.resourceId]
        if (
            occupancy != null &&
            occupancy.availability == ASPIRA_OCCUPANCY_AVAILABLE &&
            !occupancy.filtered
        ) {
            availableReservableIds += resource.rid
        }
    }
    availableReservableIds.sort()
    val availableCount = availableReservableIds.size
    val total = resources.size
    val status =
        when {
            total == 0 -> "closed"
            availableCount > 0 -> "available"
            else -> "booked"
        }
    return DayClassification(
        date = arrival.toString(),
        status = status,
        availableCount = availableCount,
        total = total,
        availableReservableIds = availableReservableIds,
    )
}

private fun classifyResourceArrivalDay(
    resourceDays: List<Int>,
    d: Int,
    date: String,
    reservableId: String? = null,
): DayClassification {
    val arrivalCls = AspiraStatus.classify(resourceDays[d])
    return when (arrivalCls) {
        "closed" ->
            DayClassification(
                date = date,
                status = "closed",
                availableCount = 0,
                total = 1,
                availableReservableIds = knownEmptyReservableIds(reservableId),
            )
        "available", "partial" -> {
            DayClassification(
                date = date,
                status = "available",
                availableCount = 1,
                total = 1,
                availableReservableIds = reservableId?.let(::listOf).orEmpty(),
            )
        }
        else ->
            DayClassification(
                date = date,
                status = "booked",
                availableCount = 0,
                total = 1,
                availableReservableIds = knownEmptyReservableIds(reservableId),
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
)

private data class CachedOccupancyDay(
    val hit: Boolean,
    val ageSeconds: Long,
    val ttlSeconds: Long,
)

private const val ASPIRA_OCCUPANCY_AVAILABLE = 0

private fun knownEmptyReservableIds(reservableId: String?): List<String>? = if (reservableId == null) null else emptyList()

/**
 * Classify one date across every sub-area. Sub-areas missing a status on the
 * date are not counted in `total` (no data, not "available 0 of 0").
 */
private fun classifyArrivalDay(
    subAreas: List<List<Int>>,
    d: Int,
    date: String,
): DayClassification {
    var available = 0
    var booked = 0
    var closed = 0
    for (subDays in subAreas) {
        if (d >= subDays.size) continue
        val arrivalCls = AspiraStatus.classify(subDays[d])
        when (arrivalCls) {
            "closed" -> closed++
            "available", "partial" -> available++
            else -> booked++
        }
    }
    val total = available + booked + closed
    val status =
        when {
            total == 0 -> "closed"
            closed == total -> "closed"
            available > 0 -> "available"
            else -> "booked"
        }
    return DayClassification(date, status, available, total)
}

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
