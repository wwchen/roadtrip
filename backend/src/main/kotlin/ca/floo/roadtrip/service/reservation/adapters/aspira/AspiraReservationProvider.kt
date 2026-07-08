package ca.floo.roadtrip.service.reservation.adapters.aspira

import ca.floo.roadtrip.clients.aspira.AspiraAvailabilityClient
import ca.floo.roadtrip.clients.aspira.AspiraException
import ca.floo.roadtrip.models.availability.AvailabilityObservationBatch
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.models.domain.Reservable
import ca.floo.roadtrip.service.reservation.AvailabilityClient
import ca.floo.roadtrip.service.reservation.CapabilityLimit
import ca.floo.roadtrip.service.reservation.CapabilityTimeUnit
import ca.floo.roadtrip.service.reservation.CatalogReservableRef
import ca.floo.roadtrip.service.reservation.ReservationProvider
import ca.floo.roadtrip.service.reservation.ReservationProviderCapabilities
import ca.floo.roadtrip.service.reservation.ReservationProviderError
import ca.floo.roadtrip.service.reservation.ReservationProviderId
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
class AspiraReservationProvider(
    private val tenant: AspiraTenant,
    private val client: AspiraAvailabilityClient,
    /**
     * When true, catalog availability with a known `resourceLocationId` uses
     * the per-arrival-day `/api/occupancy` search. The normal per-day catalog
     * path uses `/api/availability/map`, because occupancy is a stay-level
     * search result and does not return one status row per arrival date.
     */
    private val occupancyEnabled: Boolean = false,
) : ReservationProvider,
    AvailabilityClient {
    override val id: ReservationProviderId = ReservationProviderId.ASPIRA

    override val capabilities: ReservationProviderCapabilities =
        ReservationProviderCapabilities(
            supportsAvailability = true,
            // Alert poller is rec.gov-only today; Aspira polling is planned
            // (see RFC 0007). Keep this honest until the poller adapter lands.
            supportsAlerts = false,
            bookingHorizonDays = tenant.bookingHorizonDays,
            maxPollWindowDays = ASPIRA_MAX_POLL_WINDOW_DAYS,
            bookingHorizon = CapabilityLimit(tenant.bookingHorizonDays, CapabilityTimeUnit.DAY),
            fetchWindowCap = CapabilityLimit(ASPIRA_MAX_POLL_WINDOW_DAYS, CapabilityTimeUnit.DAY),
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
                    rid = it.rid,
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
     *  neither the reservable's own ref nor [parentRef] carries the ids the
     *  link needs. */
    override fun bookingUrlTemplate(
        reservable: Reservable,
        parentRef: ProviderRef,
    ): String? = AspiraBookingUrl.templateFor(tenant.host, reservable.providerRef, parentRef)

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
            ?: throw ReservationProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")

    private fun intOrThrow(
        label: String,
        value: Long,
    ): Int {
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw ReservationProviderError.UpstreamUnavailable(
                IllegalStateException("aspira $label $value does not fit in Int"),
            )
        }
        return value.toInt()
    }

    private inline fun <T> runWithErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (e: ReservationProviderError) {
            throw e
        } catch (e: AspiraException) {
            when {
                e.httpStatus == 429 -> throw ReservationProviderError.RateLimited(e)
                e.httpStatus == 503 || e.message?.contains("WAF") == true ->
                    throw ReservationProviderError.UpstreamBlocked(e)
                else -> throw ReservationProviderError.UpstreamUnavailable(e)
            }
        } catch (e: Exception) {
            throw ReservationProviderError.UpstreamUnavailable(e)
        }
}
