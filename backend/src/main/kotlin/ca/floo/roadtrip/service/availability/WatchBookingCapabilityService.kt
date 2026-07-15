package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget

internal data class WatchBookingSupport(
    val scopedCount: Int,
    val unsupportedCount: Int,
) {
    val supported: Boolean get() = scopedCount > 0 && unsupportedCount == 0
}

internal class WatchBookingCapabilityService(
    private val availabilityTargets: AvailabilityTargetResolver,
    private val bookingTargets: AvailabilityBookingTargetResolver,
) {
    fun supportFor(
        action: BookingAction,
        campsites: List<CampsiteAvailabilityTarget>,
    ): WatchBookingSupport {
        val unsupported =
            campsites.count { campsite ->
                val resolved = availabilityTargets.resolve(campsite) ?: return@count true
                bookingTargets.targetFor(action, resolved) == null
            }
        return WatchBookingSupport(scopedCount = campsites.size, unsupportedCount = unsupported)
    }

    fun supportedActions(campsites: List<CampsiteAvailabilityTarget>): Set<BookingAction> =
        BookingAction.entries
            .filter { supportFor(it, campsites).supported }
            .toSet()
}
