package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.domain.CampsiteAvailabilityTarget

internal data class WatchCapabilitySupport(
    val scopedCount: Int,
    val unsupportedCount: Int,
) {
    val supported: Boolean get() = scopedCount > 0 && unsupportedCount == 0
}

internal class WatchCapabilityService(
    private val availabilityTargets: AvailabilityTargetResolver,
    private val bookingTargets: AvailabilityBookingTargetResolver,
) {
    fun internalPollingSupportFor(campsites: List<CampsiteAvailabilityTarget>): WatchCapabilitySupport {
        val unsupported =
            campsites.count { campsite ->
                val resolved = availabilityTargets.resolve(campsite) ?: return@count true
                resolved.internalPollingTarget() == null
            }
        return WatchCapabilitySupport(scopedCount = campsites.size, unsupportedCount = unsupported)
    }

    fun bookingSupportFor(
        action: BookingAction,
        campsites: List<CampsiteAvailabilityTarget>,
    ): WatchCapabilitySupport {
        val unsupported =
            campsites.count { campsite ->
                val resolved = availabilityTargets.resolve(campsite) ?: return@count true
                bookingTargets.targetFor(action, resolved) == null
            }
        return WatchCapabilitySupport(scopedCount = campsites.size, unsupportedCount = unsupported)
    }

    fun supportedBookingActions(campsites: List<CampsiteAvailabilityTarget>): Set<BookingAction> =
        BookingAction.entries
            .filter { bookingSupportFor(it, campsites).supported }
            .toSet()

    fun supportedTriggerKinds(campsites: List<CampsiteAvailabilityTarget>): List<String> {
        if (!internalPollingSupportFor(campsites).supported) return emptyList()
        val bookingActions = supportedBookingActions(campsites)
        return buildList {
            add(AvailabilityTriggerKinds.SLACK_NOTIFY)
            if (BookingAction.ADD_TO_CART in bookingActions) add(AvailabilityTriggerKinds.ATC)
        }
    }
}
