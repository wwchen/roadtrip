package ca.floo.roadtrip.service.booking.adapters.recgov

import ca.floo.campsite.recgov.booker.api.availableDatesRecgov
import ca.floo.campsite.recgov.booker.api.fetchAndClassifyRecgov
import ca.floo.campsite.recgov.booker.api.monthsCovering
import ca.floo.campsite.recgov.booker.availability.CachedAvailability
import ca.floo.roadtrip.models.ProviderRef
import ca.floo.roadtrip.service.api.AvailabilityResponseDto
import ca.floo.roadtrip.service.booking.AvailabilityRequest
import ca.floo.roadtrip.service.booking.AvailableDatesRequest
import ca.floo.roadtrip.service.booking.BookingCapabilities
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.BookingProviderError
import ca.floo.roadtrip.service.booking.BookingProviderId

/**
 * rec.gov adapter. Wraps the existing per-month cache + classify pipeline in
 * `ca.floo.campsite.recgov.booker.api`. Vendor-specific error translation
 * lives here — anything that bubbles out is a [BookingProviderError].
 */
class RecGovBookingProvider(
    private val cache: CachedAvailability,
) : BookingProvider {
    override val id: BookingProviderId = BookingProviderId.RECGOV

    override val capabilities: BookingCapabilities =
        BookingCapabilities(
            supportsAvailability = true,
            supportsAlerts = true,
            // Per-alert auto-cart wiring already exists in the existing
            // recgov.booker module (Companion + cart helpers); flipping this
            // on requires only the AutoBooker port adapter, which is a
            // follow-up. Conservative until that adapter ships.
            supportsAutoBook = false,
            bookingHorizonDays = RECGOV_BOOKING_HORIZON_DAYS,
        )

    override suspend fun availability(req: AvailabilityRequest): AvailabilityResponseDto {
        val recgovId = recgovIdOrThrow(req.ref)
        // The classifier looks up to (minNights - 1) days past the visible
        // window's last day to determine whether the last day is bookable for
        // a stay. Pull months that cover the rolling window so the lookup
        // doesn't truncate at the edge.
        val rollingEnd = req.start.plusDays((req.days + req.minNights - 2).toLong())
        val months = monthsCovering(req.start, rollingEnd)
        return runWithErrorMapping {
            fetchAndClassifyRecgov(
                cache = cache,
                recgovId = recgovId,
                today = req.start,
                days = req.days,
                months = months,
                force = req.force,
                minNights = req.minNights,
            )
        }
    }

    override suspend fun availableDates(req: AvailableDatesRequest): List<String> {
        val recgovId = recgovIdOrThrow(req.ref)
        return runWithErrorMapping {
            availableDatesRecgov(cache, recgovId, req.start, req.nights)
        }
    }

    private fun recgovIdOrThrow(ref: ProviderRef): String =
        when (ref) {
            is ProviderRef.RecGov -> ref.recgovId
            else -> throw BookingProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")
        }

    private inline fun <T> runWithErrorMapping(block: () -> T): T =
        try {
            block()
        } catch (e: BookingProviderError) {
            throw e
        } catch (e: Exception) {
            // The recgov client's exception types aren't a single hierarchy
            // (some throw plain Exception with rate-limit text). Pattern-
            // match on message text the same way the legacy mapper did, but
            // produce typed BookingProviderError so the route doesn't need
            // to know the upstream's quirks.
            val msg = e.message.orEmpty()
            when {
                msg.contains("429") || msg.contains("rate") -> throw BookingProviderError.RateLimited(e)
                else -> throw BookingProviderError.UpstreamUnavailable(e)
            }
        }

    companion object {
        /** rec.gov exposes 6 months of inventory at any time. */
        private const val RECGOV_BOOKING_HORIZON_DAYS: Int = 180
    }
}
