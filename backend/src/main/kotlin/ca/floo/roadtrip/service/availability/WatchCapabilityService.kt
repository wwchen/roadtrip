package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AvailabilityWatchCapabilitiesDto
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.service.settings.RecGovCredentialsConfigured

internal data class WatchCapabilitySupport(
    val scopedCount: Int,
    val unsupportedCount: Int,
) {
    val supported: Boolean get() = scopedCount > 0 && unsupportedCount == 0
}

/**
 * What a proposed watch over this scope could actually do.
 *
 * Two different questions, deliberately kept apart. `booking_actions` is a
 * property of the *scope*: does this inventory have a cart at all. Trigger kinds
 * are a property of the scope **and the person asking**: `atc` needs the scope
 * to support `ADD_TO_CART` and the requester to have rec.gov credentials stored,
 * because a hold lands in their account. An anonymous or magic-link reader
 * simply does not see `atc` — absence, never an error — while `booking_actions`
 * still says the cart exists, which is what lets the editor distinguish "this
 * campground cannot be held" from "add your credentials in Settings".
 *
 * Gating is on *configured*, not *proven working*: wrong credentials surface at
 * test time in Settings or at fire time in the failure notification.
 */
internal class WatchCapabilityService(
    private val availabilityTargets: AvailabilityTargetResolver,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val notificationTriggerKinds: List<String> =
        listOf(
            AvailabilityTriggerKinds.SLACK_NOTIFY,
            AvailabilityTriggerKinds.EMAIL_NOTIFY,
        ),
    /** Null where no credential custodian is wired: `atc` is then never offered. */
    private val recgovCredentials: RecGovCredentialsConfigured? = null,
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

    fun supportedTriggerKinds(
        campsites: List<Campsite>,
        requester: UserId?,
    ): List<String> {
        if (!internalPollingSupportFor(campsites).supported) return emptyList()
        val bookingActions = supportedBookingActions(campsites)
        return buildList {
            addAll(notificationTriggerKinds)
            if (BookingAction.ADD_TO_CART in bookingActions && canFulfilAddToCart(requester)) {
                add(AvailabilityTriggerKinds.ATC)
            }
        }
    }

    /** Whether *this* requester could actually be the account a hold lands in. */
    fun canFulfilAddToCart(requester: UserId?): Boolean {
        val user = requester ?: return false
        return recgovCredentials?.isConfigured(user) == true
    }

    fun capabilitiesFor(
        campsites: List<Campsite>,
        requester: UserId?,
    ): AvailabilityWatchCapabilitiesDto {
        val bookingActions = supportedBookingActions(campsites)
        return AvailabilityWatchCapabilitiesDto(
            triggerKinds = supportedTriggerKinds(campsites, requester),
            bookingActions = BookingAction.entries.filter { it in bookingActions }.map { it.wireValue },
        )
    }
}
