package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.bookingProvidersById

/**
 * Bridges availability resolution to booking resolution.
 *
 * Availability can be served by one provider while booking happens on another.
 * This resolver walks the ordered availability candidates and asks each
 * booking adapter to translate the candidate into a provider-owned booking
 * target.
 */
internal class AvailabilityBookingTargetResolver(
    private val bookings: List<BookingProvider>,
) {
    private val bookingsById = bookings.bookingProvidersById()

    fun targetFor(
        action: BookingAction,
        resolved: ResolvedAvailabilityTarget,
    ): BookingTarget? =
        resolved.candidates
            .asSequence()
            .flatMap { candidate ->
                bookings
                    .asSequence()
                    .mapNotNull { it.targetFor(candidate.parentRef, candidate.catalogRef) }
            }.firstOrNull { target ->
                bookingsById[target.providerId]?.can(action, target) == true
            }
}
