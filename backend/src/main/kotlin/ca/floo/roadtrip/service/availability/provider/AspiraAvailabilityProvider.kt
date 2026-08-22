package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.model.api.AvailabilityErrorDto
import ca.floo.roadtrip.model.availability.AvailabilityCacheBlock
import ca.floo.roadtrip.model.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.model.availability.AvailabilityProviderCapabilities
import ca.floo.roadtrip.model.availability.AvailabilityProviderError
import ca.floo.roadtrip.model.availability.AvailabilityStatus
import ca.floo.roadtrip.model.availability.CampsiteDayObservation
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.aspira.AspiraStatus
import ca.floo.roadtrip.service.api.availabilityErrorDto
import ca.floo.roadtrip.support.AspiraException
import io.ktor.http.HttpStatusCode
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private const val ASPIRA_BOOKING_HORIZON_DAYS = 365
private const val ASPIRA_MAX_POLL_WINDOW_DAYS = 30

/** Aspira's Azure WAF answers a bot challenge with 401/403/503 — or a 200
 *  carrying a challenge page, which the client rethrows with "WAF" in the
 *  message. Both shapes mean "blocked", not "upstream is down". */
private val aspiraBlockedStatuses = setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_SERVICE_UNAVAILABLE)
private const val WAF_MESSAGE_MARKER = "WAF"

class AspiraAvailabilityProvider(
    private val tenants: Map<String, AspiraTenant>,
    private val availabilityClient: AspiraAvailabilityClient,
    private val enabled: Boolean,
    private val occupancyEnabled: Boolean = false,
) : AvailabilityProvider {
    private val log = LoggerFactory.getLogger(javaClass)

    override val id: BookingProvider = BookingProvider.ASPIRA

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsInternalPolling = true,
            bookingHorizonDays = ASPIRA_BOOKING_HORIZON_DAYS,
            maxPollWindowDays = ASPIRA_MAX_POLL_WINDOW_DAYS,
        )

    override fun isEnabled(): Boolean = enabled

    override fun supportsCampground(campground: Campground): Boolean {
        val provider = campground.bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return false
        val ref = campground.bookingProviderRef?.let { BookingProviderRef.parse(provider, it) } ?: return false
        return isEnabled() && ref is BookingProviderRef.Aspira && ref.tenant in tenants
    }

    override suspend fun availability(
        campground: Campground,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val aspiraRef = aspiraRefOrThrow(campground)
        val tenant = tenantForRef(aspiraRef)
        val mapId = mapIdOrThrow(aspiraRef.mapId)
        return runWithErrorMapping {
            fetchAvailability(
                host = tenant.host,
                mapId = mapId,
                startDate = startDate,
                endDate = endDate,
                campsiteVendor = tenant.vendorCode,
            )
        }
    }

    override suspend fun catalogAvailability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val aspiraRef = aspiraRefOrThrow(campground)
        val tenant = tenantForRef(aspiraRef)
        val parentMapId = mapIdOrThrow(aspiraRef.mapId)
        // A campground's own ref names one map node, but a park's sites are
        // spread across sibling maps (Alice Lake: 55 in "A", 41 in "B", 12
        // walk-in, 2 group). Only the campsite's ref knows which one holds it,
        // and a parent node answers with no resource rows at all — so pinning
        // every site to the parent's map left every site outside it with no
        // availability data.
        //
        // Falling back to the parent map is how that silent failure looked, so
        // count the fallbacks and say so: a campsite ref that predates the
        // four-part format (V45 wrote `tenant:id`, the ETL rewrites it in full)
        // parses to null here and would quietly restore the bug.
        var fellBackToParentMap = 0
        val targets =
            campsites.map { campsite ->
                val ref = campsite.dataProviderRef
                val campsiteMapId = campsite.aspiraBookingRef(aspiraRef.tenant)?.mapId?.toIntInRangeOrNull()
                if (campsiteMapId == null) fellBackToParentMap++
                AspiraCatalogCampsite(
                    campsiteId = campsite.id,
                    resourceId = campsite.aspiraResourceId(),
                    mapId = campsiteMapId ?: parentMapId,
                    resourceLocationId =
                        when (ref) {
                            is DataProviderRef.AspiraCampsite -> ref.resourceLocationId.toInt()
                            is DataProviderRef.BcParksCampsite -> ref.resourceLocationId.toInt()
                            else -> null
                        },
                )
            }
        if (fellBackToParentMap > 0) {
            log.warn(
                "aspira campsites without a usable own map id, using parent map instead: " +
                    "count={} of {} campground={} tenant={} parentMapId={}",
                fellBackToParentMap,
                campsites.size,
                campground.id,
                aspiraRef.tenant,
                parentMapId,
            )
        }
        val resourceLocationId =
            aspiraRef.resourceLocationId?.let { intOrThrow("resourceLocationId", it) }
                ?: targets.mapNotNull { it.resourceLocationId }.distinct().singleOrNull()
        return runWithErrorMapping {
            if (occupancyEnabled && resourceLocationId != null) {
                fetchCatalogOccupancy(
                    host = tenant.host,
                    parentMapId = parentMapId,
                    resourceLocationId = resourceLocationId,
                    campsites = targets,
                    today = startDate,
                    days = ChronoUnit.DAYS.between(startDate, endDate).toInt(),
                )
            } else {
                fetchCatalog(
                    host = tenant.host,
                    parentMapId = parentMapId,
                    campsites = targets,
                    startDate = startDate,
                    endDate = endDate,
                )
            }
        }
    }

    override fun vendorSiteIdFor(campsite: Campsite): String = campsite.aspiraResourceId()

    override fun reservationUrlTemplate(
        campsite: Campsite,
        parentRef: BookingProviderRef,
    ): String? {
        val aspiraRef = parentRef as? BookingProviderRef.Aspira ?: return null
        val tenant = tenants[aspiraRef.tenant] ?: return null
        return AspiraBookingUrl.templateFor(tenant.host, aspiraRef.mapId, aspiraRef.resourceLocationId, parentRef)
    }

    private suspend fun fetchAvailability(
        host: String,
        mapId: Int,
        startDate: LocalDate,
        endDate: LocalDate,
        campsiteVendor: String? = null,
    ): AvailabilityObservationBatch {
        val days = daysBetween(startDate, endDate)
        val observedAt = Instant.now()
        val data = availabilityClient.fetch(host, mapId, startDate, endDate.minusDays(1))
        return AvailabilityObservationBatch(
            provider = "aspira",
            startDate = startDate,
            endDate = endDate,
            observations = observationsFromAvailability(data, startDate, days, observedAt, campsiteVendor),
            cacheBlock = directFetchCacheBlock(),
            host = host,
            mapId = mapId.toString(),
        )
    }

    private suspend fun fetchCatalog(
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
        if (targets.isEmpty()) {
            return fetchAvailability(
                host = host,
                mapId = parentMapId,
                startDate = startDate,
                endDate = endDate,
            )
        }

        val observedAt = Instant.now()
        val dataByMap = mutableMapOf<Int, AspiraAvailability>()
        for (mapId in targets.map { it.mapId }.distinct()) {
            dataByMap[mapId] = availabilityClient.fetch(host, mapId, startDate, endDate.minusDays(1))
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

    private suspend fun fetchCatalogOccupancy(
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
                val data = availabilityClient.fetchOccupancy(host, resourceLocationId, arrival, checkout)
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

    private fun tenantForRef(ref: BookingProviderRef.Aspira): AspiraTenant =
        tenants[ref.tenant]
            ?: throw AvailabilityProviderError.Misconfigured(
                providerId = id.name.lowercase(),
                reason = "tenant '${ref.tenant}' is not configured",
                cause = IllegalArgumentException("aspira tenant '${ref.tenant}' is not configured"),
            )

    private fun mapIdOrThrow(mapId: Long): Int = intOrThrow("mapId", mapId)

    private fun aspiraRefOrThrow(campground: Campground): BookingProviderRef.Aspira {
        val provider = campground.bookingProvider?.let(BookingProvider::fromIdOrNull)
        val ref = provider?.let { campground.bookingProviderRef?.let { r -> BookingProviderRef.parse(it, r) } }
        return (ref as? BookingProviderRef.Aspira)
            ?: throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), campground.bookingProvider ?: "null")
    }

    private fun intOrThrow(
        label: String,
        value: Long,
    ): Int {
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw AvailabilityProviderError.Misconfigured(
                providerId = id.name.lowercase(),
                reason = "$label $value does not fit in Int",
                cause = IllegalStateException("aspira $label $value does not fit in Int"),
            )
        }
        return value.toInt()
    }

    private inline fun <T> runWithErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (e: AvailabilityProviderError) {
            throw e
        } catch (e: AspiraException) {
            throw upstreamAvailabilityError(
                cause = e,
                httpStatus = e.httpStatus,
                blockedStatuses = aspiraBlockedStatuses,
                blockedMessageMarker = WAF_MESSAGE_MARKER,
            )
        } catch (e: Exception) {
            throw AvailabilityProviderError.UpstreamUnavailable(e)
        }
}

internal fun mapAspiraUpstreamError(e: AspiraException): Pair<HttpStatusCode, AvailabilityErrorDto> {
    val status = e.httpStatus
    return when {
        status == HTTP_TOO_MANY_REQUESTS ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("rate_limited", upstreamStatus = status)
        status == HTTP_SERVICE_UNAVAILABLE || (e.message?.contains(WAF_MESSAGE_MARKER) == true) ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_blocked", upstreamStatus = status)
        else ->
            HttpStatusCode.ServiceUnavailable to
                availabilityErrorDto("upstream_5xx", upstreamStatus = status)
    }
}

/**
 * The campsite's own Aspira ref, but only when it belongs to the same tenant as
 * the campground. A mis-tagged campsite would otherwise send its map id to a
 * different tenant's host, where the same integer names a different park.
 */
private fun Campsite.aspiraBookingRef(parentTenant: String?): BookingProviderRef.Aspira? {
    val provider = bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return null
    val ref = bookingProviderRef?.let { BookingProviderRef.parse(provider, it) } as? BookingProviderRef.Aspira
    return ref?.takeIf { it.tenant == parentTenant }
}

/** Narrow to Int without throwing: one odd campsite must not fail its campground. */
private fun Long.toIntInRangeOrNull(): Int? = takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

private fun Campsite.aspiraResourceId(): String =
    when (val ref = dataProviderRef) {
        is DataProviderRef.AspiraCampsite -> ref.resourceLocationId.toString()
        is DataProviderRef.BcParksCampsite -> ref.resourceLocationId.toString()
        else -> dataProviderRef.serialize()
    }

private fun observationsFromAvailability(
    avail: AspiraAvailability,
    start: LocalDate,
    days: Int,
    observedAt: Instant,
    campsiteVendor: String? = null,
): List<CampsiteDayObservation> {
    if (campsiteVendor != null && avail.byResource.isNotEmpty()) {
        return observationsFromResourceCatalog(avail.byResource, start, days, observedAt)
    }
    val sub = avail.byMapLink.values.toList()
    val rollup = avail.parkRollup
    return if (sub.isNotEmpty()) {
        observationsFromIndexedStatusRows(sub, start, days, observedAt)
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
            status = statusAt(resourceDays, d),
        )
    }

private fun observationsFromResourceCatalog(
    byResource: Map<String, List<Int>>,
    start: LocalDate,
    days: Int,
    observedAt: Instant,
): List<CampsiteDayObservation> =
    byResource.flatMap { (_, resourceDays) ->
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
                status = resource.days?.let { statusAt(it, d) } ?: AvailabilityStatus.UNKNOWN,
            )
        }
    }

private fun observationsFromOccupancyCatalogArrivalDay(
    resources: List<AspiraCatalogCampsite>,
    occupancyRows: List<ca.floo.roadtrip.client.aspira.AspiraResourceOccupancy>,
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
                else -> AspiraStatus.classifyOccupancy(occupancy.availability)
            }
        CampsiteDayObservation(
            campsiteId = resource.campsiteId,
            date = arrival,
            observedAt = observedAt,
            status = status,
        )
    }
}

private data class CatalogResourceDays(
    val campsiteId: Long,
    val days: List<Int>?,
    val observedAt: Instant,
)

private fun observationsFromIndexedStatusRows(
    rows: List<List<Int>>,
    start: LocalDate,
    days: Int,
    observedAt: Instant,
): List<CampsiteDayObservation> =
    rows.flatMapIndexed { _, statuses ->
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

private fun daysBetween(
    startDate: LocalDate,
    endDate: LocalDate,
): Int = ChronoUnit.DAYS.between(startDate, endDate).toInt()

private fun directFetchCacheBlock(): AvailabilityCacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L)
