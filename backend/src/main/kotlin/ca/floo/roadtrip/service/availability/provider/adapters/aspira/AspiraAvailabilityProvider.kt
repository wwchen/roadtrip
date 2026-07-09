package ca.floo.roadtrip.service.availability.provider.adapters.aspira

import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraException
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.Campsite
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.provider.AvailabilityClient
import ca.floo.roadtrip.service.availability.provider.AvailabilityProvider
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderCapabilities
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderError
import ca.floo.roadtrip.service.availability.provider.AvailabilityProviderId
import ca.floo.roadtrip.service.availability.provider.CatalogReservableRef
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Widest single-tick poll window for Aspira. Latent until the Aspira alert
 * poller lands (see RFC 0007) — `supportsAlerts` is still false — but declared
 * honestly so the capability is complete. Conservative default; tune per
 * tenant when polling turns on.
 */
private const val ASPIRA_MAX_POLL_WINDOW_DAYS = 30

/**
 * Aspira NextGen adapter. One adapter *class* for the whole vendor; one
 * adapter *instance* per upstream host (Parks Canada, BC Parks, WA State
 * Parks). Tenant-shaped data — host, vendor code, booking horizon —
 * lives in [AspiraTenant], not in code branches.
 *
 * The downstream classifier (`fetchAspiraAvailabilityObservations`) takes `mapId: Int`;
 * the column type is `Long`. We narrow at the boundary and reject
 * out-of-range values to surface the truncation rather than silently
 * dropping the high bits.
 *
 * Deciding whether to serve stored data or call this adapter live is handled
 * above it by [ca.floo.roadtrip.service.api.AvailabilityLoader].
 */
class AspiraAvailabilityProvider(
    private val tenant: AspiraTenant,
    private val client: AspiraAvailabilityClient,
    /**
     * When true, catalog availability with a known `resourceLocationId` uses
     * the per-arrival-day `/api/occupancy` search. The normal per-day catalog
     * path uses `/api/availability/map`, because occupancy is a stay-level
     * search result and does not return one status row per arrival date.
     */
    private val occupancyEnabled: Boolean = false,
) : AvailabilityProvider,
    AvailabilityClient {
    override val id: AvailabilityProviderId = AvailabilityProviderId.ASPIRA

    override val capabilities: AvailabilityProviderCapabilities =
        AvailabilityProviderCapabilities(
            supportsAvailability = true,
            // Alert poller is rec.gov-only today; Aspira polling is planned
            // (see RFC 0007). Keep this honest until the poller adapter lands.
            supportsAlerts = false,
            bookingHorizonDays = tenant.bookingHorizonDays,
            maxPollWindowDays = ASPIRA_MAX_POLL_WINDOW_DAYS,
        )

    override suspend fun availability(
        ref: ProviderRef,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val mapId = mapIdOrThrow(ref)
        return runWithErrorMapping {
            fetchAspiraAvailabilityObservations(
                client = client,
                host = tenant.host,
                mapId = mapId,
                startDate = startDate,
                endDate = endDate,
                reservableVendor = tenant.vendorCode,
            )
        }
    }

    override suspend fun catalogAvailability(
        ref: ProviderRef,
        reservables: List<CatalogReservableRef>,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val aspiraRef = aspiraRefOrThrow(ref)
        val parentMapId = mapIdOrThrow(aspiraRef.mapId)
        val targets =
            reservables.map {
                AspiraCatalogReservable(
                    campsiteId = it.campsiteId,
                    resourceId = it.vendorId,
                    mapId = it.mapId?.let(::mapIdOrThrow),
                    resourceLocationId = it.resourceLocationId?.let { value -> intOrThrow("resourceLocationId", value) },
                )
            }
        val resourceLocationId =
            aspiraRef.resourceLocationId?.let { intOrThrow("resourceLocationId", it) }
                ?: targets.mapNotNull { it.resourceLocationId }.distinct().singleOrNull()
        return runWithErrorMapping {
            if (occupancyEnabled && resourceLocationId != null) {
                fetchAspiraCatalogOccupancyObservations(
                    client = client,
                    host = tenant.host,
                    parentMapId = parentMapId,
                    resourceLocationId = resourceLocationId,
                    reservables = targets,
                    today = startDate,
                    days = ChronoUnit.DAYS.between(startDate, endDate).toInt(),
                )
            } else {
                fetchAspiraCatalogObservations(
                    client = client,
                    host = tenant.host,
                    parentMapId = parentMapId,
                    reservables = targets,
                    startDate = startDate,
                    endDate = endDate,
                )
            }
        }
    }

    /** goingtocamp `create-booking/results` deep link for this tenant's host;
     *  the concrete-date [bookingUrl] fills the window placeholders. Null when
     *  neither the campsite's own ref nor [parentRef] carries the ids the
     *  link needs. */
    override fun bookingUrlTemplate(
        campsite: Campsite,
        parentRef: ProviderRef,
    ): String? = AspiraBookingUrl.templateFor(tenant.host, campsite.providerRef, parentRef)

    override suspend fun reservableAvailability(
        ref: ProviderRef,
        vendorId: String,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AvailabilityObservationBatch {
        val mapId = mapIdOrThrow(ref)
        return runWithErrorMapping {
            fetchAspiraResourceObservations(
                client = client,
                host = tenant.host,
                mapId = mapId,
                resourceId = vendorId,
                reservableVendor = tenant.vendorCode,
                startDate = startDate,
                endDate = endDate,
            )
        }
    }

    /**
     * Pull the map id and narrow Long → Int. Real Aspira ids fit comfortably
     * in 32 bits; rejecting an out-of-range value loudly is better than
     * silent truncation.
     */
    private fun mapIdOrThrow(ref: ProviderRef): Int {
        val ar = aspiraRefOrThrow(ref)
        return intOrThrow("mapId", ar.mapId)
    }

    private fun mapIdOrThrow(mapId: Long): Int = intOrThrow("mapId", mapId)

    private fun aspiraRefOrThrow(ref: ProviderRef): ProviderRef.Aspira =
        (ref as? ProviderRef.Aspira)
            ?: throw AvailabilityProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")

    private fun intOrThrow(
        label: String,
        value: Long,
    ): Int {
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw AvailabilityProviderError.UpstreamUnavailable(
                IllegalStateException("aspira $label $value does not fit in Int"),
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
            when {
                e.httpStatus == 429 -> throw AvailabilityProviderError.RateLimited(e)
                e.httpStatus == 503 || e.message?.contains("WAF") == true ->
                    throw AvailabilityProviderError.UpstreamBlocked(e)
                else -> throw AvailabilityProviderError.UpstreamUnavailable(e)
            }
        } catch (e: Exception) {
            throw AvailabilityProviderError.UpstreamUnavailable(e)
        }
}
