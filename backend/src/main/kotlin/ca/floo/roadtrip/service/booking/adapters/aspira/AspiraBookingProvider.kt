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
 * Aspira NextGen adapter. One instance per host (Parks Canada, BC Provincial,
 * WA State Parks). The host is bound at construction so route-time dispatch
 * doesn't carry the registry's host map down to every call.
 *
 * The downstream classifier (`fetchAndClassifyAspira`) takes `mapId: Int`;
 * the column type is `Long`. We narrow at the boundary and reject
 * out-of-range values to surface the truncation rather than silently
 * dropping the high bits.
 */
class AspiraBookingProvider(
    override val id: BookingProviderId,
    private val host: String,
    private val cache: CachedAspiraAvailability,
) : BookingProvider {
    override val capabilities: BookingCapabilities =
        BookingCapabilities(
            supportsAvailability = true,
            // Alert poller is rec.gov-only today; Aspira polling is planned
            // (see RFC 0007). Keep this honest until the poller adapter lands.
            supportsAlerts = false,
            supportsAutoBook = false,
            bookingHorizonDays = ASPIRA_BOOKING_HORIZON_DAYS,
        )

    override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto {
        val mapId = mapIdOrThrow(req.ref)
        return runWithErrorMapping {
            fetchAndClassifyAspira(
                cache = cache,
                host = host,
                mapId = mapId,
                today = req.start,
                days = req.days,
                force = req.force,
                minNights = req.minNights,
            )
        }
    }

    override suspend fun availableDates(req: AvailableDatesRequest): List<String> {
        val mapId = mapIdOrThrow(req.ref)
        return runWithErrorMapping {
            availableDatesAspira(cache, host, mapId, req.start, req.nights)
        }
    }

    override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityResponseDto {
        val mapId = mapIdOrThrow(req.ref)
        return runWithErrorMapping {
            fetchAndClassifyAspiraResource(
                cache = cache,
                host = host,
                mapId = mapId,
                resourceId = req.vendorId,
                reservableVendor = reservableVendor(),
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

    private fun reservableVendor(): String =
        when (id) {
            BookingProviderId.ASPIRA_PC -> "aspira_pc"
            BookingProviderId.ASPIRA_BC -> "aspira_bc"
            BookingProviderId.ASPIRA_WA -> "aspira_wa"
            else -> "aspira"
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

    companion object {
        /** Aspira upstreams typically expose the next 12 months. */
        private const val ASPIRA_BOOKING_HORIZON_DAYS: Int = 365
    }
}
