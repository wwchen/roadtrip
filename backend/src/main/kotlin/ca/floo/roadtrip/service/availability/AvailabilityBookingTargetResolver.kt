package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.service.booking.BookingProviderRegistry

/**
 * Bridges availability resolution to booking resolution.
 *
 * Availability can be served by one provider while booking happens on another.
 * This resolver walks the ordered availability candidates and asks the booking
 * registry to translate each candidate into a provider-owned booking target.
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
            .mapNotNull { bookings.targetFor(action, it.parentRef, it.catalogRef) }
            .firstOrNull()
}
