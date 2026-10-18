package ca.floo.roadtrip.service.availability.provider

import ca.floo.roadtrip.client.aspira.AspiraAvailability
import ca.floo.roadtrip.client.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.model.api.HTTP_FORBIDDEN
import ca.floo.roadtrip.model.api.HTTP_SERVICE_UNAVAILABLE
import ca.floo.roadtrip.model.api.HTTP_UNAUTHORIZED
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
import ca.floo.roadtrip.model.domain.provider.BookingTenant
import ca.floo.roadtrip.model.domain.provider.DataProviderRef
import ca.floo.roadtrip.model.metadata.aspira.AspiraStatus
import ca.floo.roadtrip.support.AspiraException
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
    tenants: List<BookingTenant>,
    private val availabilityClient: AspiraAvailabilityClient,
    private val enabled: Boolean,
    private val occupancyEnabled: Boolean = false,
) : AvailabilityProvider {
    private val tenantsByCode: Map<String, BookingTenant> = tenants.byCode()

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
        val ref = claimedRef(campground) ?: return false
        return isEnabled() && ref is BookingProviderRef.Aspira && ref.tenant in tenantsByCode
    }

    override suspend fun availability(
        campground: Campground,
        campsites: List<Campsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val aspiraRef = aspiraRefOrThrow(campground)
        val tenant = tenantForRef(aspiraRef)
        val parentMapId = mapIdOrThrow(aspiraRef.mapId)
        // Sites sit on sibling maps, and a parent node returns no resource
        // rows — so each campsite's own map id is the one to fetch. Falling
        // back to the parent is what the bug looked like, hence the count.
        var fellBackToParentMap = 0
        val targets =
            campsites.map { campsite ->
                val campsiteMapId = campsite.aspiraBookingRef(aspiraRef.tenant)?.mapId?.toIntInRangeOrNull()
                if (campsiteMapId == null) fellBackToParentMap++
                aspiraTarget(campsite, campsiteMapId ?: parentMapId)
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
        // The campground's own ref, and nothing else. This used to fall back to
        // "the single distinct resource location across the campsites I was
        // handed", which made the path a function of the caller's list: a list
        // covering one location took the occupancy path, a wider one covering
        // several fell to `fetchCatalog`'s per-map fetches and a different
        // classifier, for the same campground and the same question. A capability
        // gate has to be a property of the campground to mean anything.
        val resourceLocationId = aspiraRef.resourceLocationId?.let { intOrThrow("resourceLocationId", it) }
        return runWithErrorMapping {
            if (occupancyEnabled && resourceLocationId != null) {
                fetchCatalogOccupancy(
                    scope = aspiraRef,
                    host = tenant.host,
                    resourceLocationId = resourceLocationId,
                    campsites = targets,
                    today = startDate,
                    days = ChronoUnit.DAYS.between(startDate, endDate).toInt(),
                )
            } else {
                fetchCatalog(
                    scope = aspiraRef,
                    host = tenant.host,
                    campsites = targets,
                    startDate = startDate,
                    endDate = endDate,
                )
            }
        }
    }

    private fun aspiraTarget(
        campsite: Campsite,
        mapId: Int,
    ): AspiraCatalogCampsite =
        AspiraCatalogCampsite(
            campsiteId = campsite.id,
            resourceId = campsite.aspiraResourceId(),
            mapId = mapId,
            resourceLocationId =
                when (val ref = campsite.dataProviderRef) {
                    is DataProviderRef.AspiraCampsite -> ref.resourceLocationId.toInt()
                    is DataProviderRef.BcParksCampsite -> ref.resourceLocationId.toInt()
                    else -> null
                },
        )

    override fun vendorSiteIdFor(campsite: Campsite): String = campsite.aspiraResourceId()

    override fun reservationUrlTemplate(
        campsite: Campsite,
        parentRef: BookingProviderRef,
    ): String? {
        val aspiraRef = parentRef as? BookingProviderRef.Aspira ?: return null
        val tenant = tenantsByCode[aspiraRef.tenant] ?: return null
        // A park's sites are split across sibling loop maps, so the site's own
        // map is the grid its "Book" link belongs on; the parent fills the gaps.
        val campsiteRef = campsite.aspiraBookingRef(aspiraRef.tenant)
        return AspiraBookingUrl.templateFor(
            tenant.host,
            campsiteRef?.mapId,
            campsiteRef?.resourceLocationId,
            parentRef,
        )
    }

    private suspend fun fetchCatalog(
        scope: BookingProviderRef.Aspira,
        host: String,
        campsites: List<AspiraCatalogCampsite>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val days = daysBetween(startDate, endDate)
        val targets = campsites.distinctBy { it.campsiteId }
        if (targets.isEmpty()) {
            return emptyBatch(scope, startDate, endDate)
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
            scope = scope,
        )
    }

    private suspend fun fetchCatalogOccupancy(
        scope: BookingProviderRef.Aspira,
        host: String,
        resourceLocationId: Int,
        campsites: List<AspiraCatalogCampsite>,
        today: LocalDate,
        days: Int,
    ): AvailabilityObservationBatch {
        val targets = campsites.distinctBy { it.campsiteId }
        if (targets.isEmpty()) {
            return emptyBatch(scope, today, today.plusDays(days.toLong()))
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
            scope = scope,
        )
    }

    private fun tenantForRef(ref: BookingProviderRef.Aspira): BookingTenant =
        tenantsByCode[ref.tenant]
            ?: throw AvailabilityProviderError.Misconfigured(
                providerId = id.name.lowercase(),
                reason = "tenant '${ref.tenant}' is not configured",
                cause = IllegalArgumentException("aspira tenant '${ref.tenant}' is not configured"),
            )

    private fun mapIdOrThrow(mapId: Long): Int = intOrThrow("mapId", mapId)

    private fun aspiraRefOrThrow(campground: Campground): BookingProviderRef.Aspira =
        (claimedRef(campground) as? BookingProviderRef.Aspira)
            ?: throw AvailabilityProviderError.WrongRefType(id.name.lowercase(), campground.bookingProvider ?: "null")

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

    private suspend inline fun <T> runWithErrorMapping(crossinline block: suspend () -> T): T =
        mapUpstreamErrors(
            vendorError = { e: AspiraException ->
                upstreamAvailabilityError(
                    cause = e,
                    httpStatus = e.httpStatus,
                    blockedStatuses = aspiraBlockedStatuses,
                    blockedMessageMarker = WAF_MESSAGE_MARKER,
                )
            },
        ) { block() }
}

/** Same-tenant only: the same map id names a different park on another host. */
private fun Campsite.aspiraBookingRef(parentTenant: String?): BookingProviderRef.Aspira? {
    val provider = bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return null
    val ref = bookingProviderRef?.let { BookingProviderRef.parse(provider, it) } as? BookingProviderRef.Aspira
    return ref?.takeIf { it.tenant == parentTenant }
}

/** Non-throwing: one odd campsite must not fail its campground. */
private fun Long.toIntInRangeOrNull(): Int? = takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

private fun Campsite.aspiraResourceId(): String =
    when (val ref = dataProviderRef) {
        is DataProviderRef.AspiraCampsite -> ref.resourceLocationId.toString()
        is DataProviderRef.BcParksCampsite -> ref.resourceLocationId.toString()
        else -> dataProviderRef.serialize()
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

/** A campground whose campsites resolve to nothing upstream: no base units to report. */
private fun emptyBatch(
    scope: BookingProviderRef.Aspira,
    startDate: LocalDate,
    endDate: LocalDate,
): AvailabilityObservationBatch =
    AvailabilityObservationBatch(
        provider = "aspira",
        startDate = startDate,
        endDate = endDate,
        observations = emptyList(),
        cacheBlock = directFetchCacheBlock(),
        scope = scope,
    )

private fun directFetchCacheBlock(): AvailabilityCacheBlock = AvailabilityCacheBlock(hit = false, ageSeconds = 0L, ttlSeconds = 0L)
