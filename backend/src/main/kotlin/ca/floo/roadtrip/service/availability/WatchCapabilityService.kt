package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCapabilitiesDto
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.domain.Campsite

internal data class WatchCapabilitySupport(
    val scopedCount: Int,
    val unsupportedCount: Int,
) {
    val supported: Boolean get() = scopedCount > 0 && unsupportedCount == 0
}

internal class WatchCapabilityService(
    private val availabilityTargets: AvailabilityTargetResolver,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val notificationTriggerKinds: List<String> =
        listOf(
            AvailabilityTriggerKinds.SLACK_NOTIFY,
            AvailabilityTriggerKinds.EMAIL_NOTIFY,
        ),
) {
    fun internalPollingSupportFor(campsites: List<Campsite>): WatchCapabilitySupport {
        val unsupported =
            campsites.count { campsite ->
                val resolved = availabilityTargets.resolve(campsite) ?: return@count true
                !resolved.provider.capabilities.supportsInternalPolling
            }
        return WatchCapabilitySupport(scopedCount = campsites.size, unsupportedCount = unsupported)
    }

    fun bookingSupportFor(
        action: BookingAction,
        campsites: List<Campsite>,
    ): WatchCapabilitySupport {
        val unsupported =
            campsites.count { campsite ->
                val resolved = availabilityTargets.resolve(campsite) ?: return@count true
                bookingTargets.targetFor(action, resolved) == null
            }
        return WatchCapabilitySupport(scopedCount = campsites.size, unsupportedCount = unsupported)
    }

    fun supportedBookingActions(campsites: List<Campsite>): Set<BookingAction> =
        BookingAction.entries
            .filter { bookingSupportFor(it, campsites).supported }
            .toSet()

    fun supportedTriggerKinds(campsites: List<Campsite>): List<String> {
        if (!internalPollingSupportFor(campsites).supported) return emptyList()
        val bookingActions = supportedBookingActions(campsites)
        return buildList {
            addAll(notificationTriggerKinds)
            if (BookingAction.ADD_TO_CART in bookingActions) add(AvailabilityTriggerKinds.ATC)
        }
    }

    fun capabilitiesFor(campsites: List<Campsite>): AvailabilityWatchCapabilitiesDto {
        val bookingActions = supportedBookingActions(campsites)
        return AvailabilityWatchCapabilitiesDto(
            triggerKinds = supportedTriggerKinds(campsites),
            bookingActions = BookingAction.entries.filter { it in bookingActions }.map { it.wireValue },
        )
    }
}
