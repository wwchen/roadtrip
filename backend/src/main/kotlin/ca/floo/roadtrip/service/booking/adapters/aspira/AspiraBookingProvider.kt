package ca.floo.roadtrip.service.booking.adapters.aspira

import ca.floo.roadtrip.client.AspiraException
import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.repo.CachedAspiraAvailability
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.api.availableDatesAspira
import ca.floo.roadtrip.service.api.fetchAndClassifyAspira
import ca.floo.roadtrip.service.api.fetchAndClassifyAspiraResource
import ca.floo.roadtrip.service.booking.AvailabilityRequest
import ca.floo.roadtrip.service.booking.AvailableDatesRequest
import ca.floo.roadtrip.service.booking.BookingCapabilities
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderError
import ca.floo.roadtrip.service.booking.BookingProviderId
import ca.floo.roadtrip.service.booking.ReservableAvailabilityRequest

/**
 * Aspira NextGen adapter. One adapter *class* for the whole vendor; one
 * adapter *instance* per upstream host (Parks Canada, BC Parks, WA State
 * Parks). Tenant-shaped data — host, vendor code, booking horizon —
 * lives in [AspiraTenant], not in code branches.
 *
 * The downstream classifier (`fetchAndClassifyAspira`) takes `mapId: Int`;
 * the column type is `Long`. We narrow at the boundary and reject
 * out-of-range values to surface the truncation rather than silently
 * dropping the high bits.
 */
class AspiraBookingProvider(
    private val tenant: AspiraTenant,
    private val cache: CachedAspiraAvailability,
) : BookingProvider {
    override val id: BookingProviderId = BookingProviderId.ASPIRA

    override val capabilities: BookingCapabilities =
        BookingCapabilities(
            supportsAvailability = true,
            // Alert poller is rec.gov-only today; Aspira polling is planned
            // (see RFC 0007). Keep this honest until the poller adapter lands.
            supportsAlerts = false,
            supportsAutoBook = false,
            bookingHorizonDays = tenant.bookingHorizonDays,
        )

    override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto {
        val mapId = mapIdOrThrow(req.ref)
        return runWithErrorMapping {
            fetchAndClassifyAspira(
                cache = cache,
                host = tenant.host,
                mapId = mapId,
                today = req.start,
                days = req.days,
                force = req.force,
                minNights = req.minNights,
                reservableVendor = tenant.vendorCode,
            )
        }
    }

    override suspend fun availableDates(req: AvailableDatesRequest): List<String> {
        val mapId = mapIdOrThrow(req.ref)
        return runWithErrorMapping {
            availableDatesAspira(cache, tenant.host, mapId, req.start, req.nights)
        }
    }

    override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityResponseDto {
        val mapId = mapIdOrThrow(req.ref)
        return runWithErrorMapping {
            fetchAndClassifyAspiraResource(
                cache = cache,
                host = tenant.host,
                mapId = mapId,
                resourceId = req.vendorId,
                reservableVendor = tenant.vendorCode,
                today = req.start,
                days = req.days,
                force = req.force,
                minNights = req.minNights,
            )
        }
    }

    /**
     * Pull the map id and narrow Long → Int. Real Aspira ids fit comfortably
     * in 32 bits; rejecting an out-of-range value loudly is better than
     * silent truncation.
     */
    private fun mapIdOrThrow(ref: ProviderRef): Int {
        val ar =
            (ref as? ProviderRef.Aspira)
                ?: throw BookingProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")
        if (ar.mapId !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            throw BookingProviderError.UpstreamUnavailable(
                IllegalStateException("aspira mapId ${ar.mapId} does not fit in Int"),
            )
        }
        return ar.mapId.toInt()
    }

    private inline fun <T> runWithErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (e: BookingProviderError) {
            throw e
        } catch (e: AspiraException) {
            when {
                e.httpStatus == 429 -> throw BookingProviderError.RateLimited(e)
                e.httpStatus == 503 || e.message?.contains("WAF") == true ->
                    throw BookingProviderError.UpstreamBlocked(e)
                else -> throw BookingProviderError.UpstreamUnavailable(e)
            }
        } catch (e: Exception) {
            throw BookingProviderError.UpstreamUnavailable(e)
        }
}
