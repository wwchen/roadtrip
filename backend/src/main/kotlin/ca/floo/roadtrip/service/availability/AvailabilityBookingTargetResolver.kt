package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry

/**
 * Bridges availability resolution to booking resolution.
 *
 * Availability can be served by one provider while booking happens on another.
 * This resolver walks the ordered availability candidates and asks the booking
 * registry to translate each candidate into a provider-owned booking target.
 */
internal class AvailabilityBookingTargetResolver(
    private val bookings: BookingAdapterRegistry,
) {
    fun targetFor(
        action: BookingAction,
        resolved: ResolvedAvailabilityTarget,
    ): BookingTarget? {
        for (provider in resolved.candidates) {
            val ref = provider.parentRefFor(resolved.campground) ?: continue
            val target =
                bookings.targetFor(
                    action,
                    ref,
                    resolved.campsite.id,
                    provider.vendorSiteIdFor(resolved.campsite),
                )
            if (target != null) return target
        }
        return null
    }
}
