package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.repo.AvailabilityWatchRepo

private const val UNSUPPORTED_TRIGGER_ERROR = "unsupported_trigger"
private const val ATC_EMPTY_SCOPE_DETAIL = "atc requires at least one campsite in scope"

internal fun interface WatchCapabilityValidator {
    fun validate(watch: AvailabilityWatchRepo.Watch)
}

internal object NoopWatchCapabilityValidator : WatchCapabilityValidator {
    override fun validate(watch: AvailabilityWatchRepo.Watch) = Unit
}

internal class AvailabilityWatchValidationException(
    val error: String,
    override val message: String,
) : IllegalArgumentException(message)

/**
 * Validates booking-trigger support for a persisted watch snapshot.
 *
 * This runs after the watch row and targets are written inside the mutation
 * transaction, but before alert-provider activation. Throwing rolls the watch
 * mutation back, so unsupported ATC intent never leaves a live watch behind.
 */
internal class WatchBookingCapabilityValidator(
    private val scopeResolver: WatchScopeResolver,
    private val capabilities: WatchBookingCapabilityService,
) : WatchCapabilityValidator {
    override fun validate(watch: AvailabilityWatchRepo.Watch) {
        if (watch.status != WatchStatus.ACTIVE) return
        if (AvailabilityTriggerKinds.ATC !in watch.triggerKinds) return

        val campsites = scopeResolver.resolve(watch)
        if (campsites.isEmpty()) {
            throw AvailabilityWatchValidationException(
                error = UNSUPPORTED_TRIGGER_ERROR,
                message = ATC_EMPTY_SCOPE_DETAIL,
            )
        }

        val support = capabilities.supportFor(BookingAction.ADD_TO_CART, campsites)
        if (!support.supported) {
            throw AvailabilityWatchValidationException(
                error = UNSUPPORTED_TRIGGER_ERROR,
                message = "atc is not supported for ${support.unsupportedCount} of ${support.scopedCount} scoped campsite(s)",
            )
        }
    }
}
