package ca.floo.roadtrip.service.booking.adapters.recgov

import ca.floo.campsite.recgov.booker.api.availableDatesRecgov
import ca.floo.campsite.recgov.booker.api.fetchAndClassifyRecgov
import ca.floo.campsite.recgov.booker.api.fetchAndClassifyRecgovCatalog
import ca.floo.campsite.recgov.booker.api.fetchAndClassifyRecgovReservable
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
import ca.floo.roadtrip.service.booking.CatalogAvailabilityRequest
import ca.floo.roadtrip.service.booking.ReservableAvailabilityRequest
import java.time.LocalDate

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
        val days = daysBetween(req.startDate, req.endDate)
        val minNights = 1
        // The classifier looks up to (minNights - 1) days past the visible
        // window's last day to determine whether the last day is bookable for
        // a stay. Pull months that cover the rolling window so the lookup
        // doesn't truncate at the edge.
        val rollingEnd = req.startDate.plusDays((days + minNights - 2).toLong())
        val months = monthsCovering(req.startDate, rollingEnd)
        return runWithErrorMapping {
            fetchAndClassifyRecgov(
                cache = cache,
                recgovId = recgovId,
                today = req.startDate,
                days = days,
                months = months,
                force = req.force,
                minNights = minNights,
            )
        }
    }

    override suspend fun availableDates(req: AvailableDatesRequest): List<String> {
        val recgovId = recgovIdOrThrow(req.ref)
        val days = daysBetween(req.startDate, req.endDate)
        return runWithErrorMapping {
            availableDatesRecgov(cache, recgovId, req.startDate, days)
        }
    }

    override suspend fun catalogAvailability(req: CatalogAvailabilityRequest): AvailabilityResponseDto {
        if (req.reservables.isEmpty()) {
            return availability(
                AvailabilityRequest(
                    ref = req.ref,
                    startDate = req.startDate,
                    endDate = req.endDate,
                    force = req.force,
                ),
            )
        }
        val recgovId = recgovIdOrThrow(req.ref)
        val days = daysBetween(req.startDate, req.endDate)
        val minNights = 1
        val rollingEnd = req.startDate.plusDays((days + minNights - 2).toLong())
        val months = monthsCovering(req.startDate, rollingEnd)
        return runWithErrorMapping {
            fetchAndClassifyRecgovCatalog(
                cache = cache,
                recgovId = recgovId,
                campsiteIds = req.reservables.map { it.vendorId }.toSet(),
                today = req.startDate,
                days = days,
                months = months,
                force = req.force,
                minNights = minNights,
            )
        }
    }

    override suspend fun reservableAvailability(req: ReservableAvailabilityRequest): AvailabilityResponseDto {
        val recgovId = recgovIdOrThrow(req.ref)
        val days = daysBetween(req.startDate, req.endDate)
        val minNights = 1
        val rollingEnd = req.startDate.plusDays((days + minNights - 2).toLong())
        val months = monthsCovering(req.startDate, rollingEnd)
        return runWithErrorMapping {
            fetchAndClassifyRecgovReservable(
                cache = cache,
                recgovId = recgovId,
                campsiteId = req.vendorId,
                today = req.startDate,
                days = days,
                months = months,
                force = req.force,
                minNights = minNights,
            )
        }
    }

    private fun recgovIdOrThrow(ref: ProviderRef): String =
        when (ref) {
            is ProviderRef.RecGov -> ref.recgovId
            else -> throw BookingProviderError.WrongRefType(id, ref::class.simpleName ?: "unknown")
        }

    private fun daysBetween(
        startDate: LocalDate,
        endDate: LocalDate,
    ): Int =
        java.time.temporal.ChronoUnit.DAYS
            .between(startDate, endDate)
            .toInt()

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
