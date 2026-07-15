package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.booking.BookingProviderRegistry

/**
 * Bridges availability resolution to booking resolution.
 *
 * Availability can be served by one provider while booking happens on another
 * vendor. This resolver walks the ordered availability candidates and returns
 * the first candidate whose parent/campsite identity is supported by the
 * booking-provider registry for the requested action.
 */
internal class AvailabilityBookingTargetResolver(
    private val bookings: BookingProviderRegistry,
) {
    fun targetFor(
        action: BookingAction,
        resolved: ResolvedAvailabilityTarget,
    ): BookingTarget? =
        resolved.candidates
            .asSequence()
            .mapNotNull { it.toBookingTarget() }
            .firstOrNull { bookings.can(action, it) }

    private fun ProviderCandidate.toBookingTarget(): BookingTarget? {
        val providerId = parentRef.bookingProviderId() ?: return null
        return BookingTarget(
            providerId = providerId,
            parentRef = parentRef,
            campsiteRef = catalogRef,
        )
    }

    private fun ProviderRef.bookingProviderId(): BookingProviderId? =
        when (this) {
            is ProviderRef.RecGov -> BookingProviderId.RECGOV
            is ProviderRef.Aspira -> BookingProviderId.ASPIRA
            is ProviderRef.ReserveAmerica -> BookingProviderId.RESERVEAMERICA
            is ProviderRef.ReserveCalifornia -> BookingProviderId.RESERVECALIFORNIA
            is ProviderRef.Campflare -> null
        }
}
