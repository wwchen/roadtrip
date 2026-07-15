package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.repo.AvailabilityWatchRepo

private const val UNSUPPORTED_TRIGGER_ERROR = "unsupported_trigger"
private const val ATC_EMPTY_SCOPE_DETAIL = "atc requires at least one campsite in scope"
private const val WATCH_TRIGGER_EMPTY_SCOPE_DETAIL = "watch trigger requires at least one campsite in scope"

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
 * Validates trigger support for a persisted watch snapshot.
 *
 * This runs after the watch row and targets are written inside the mutation
 * transaction, but before alert-provider activation. Throwing rolls the watch
 * mutation back, so unsupported watch intent never leaves a live watch behind.
 */
internal class WatchTriggerCapabilityValidator(
    private val scopeResolver: WatchScopeResolver,
    private val capabilities: WatchCapabilityService,
) : WatchCapabilityValidator {
    override fun validate(watch: AvailabilityWatchRepo.Watch) {
        if (watch.status != WatchStatus.ACTIVE) return
        val requiresInternalPolling =
            AvailabilityTriggerKinds.SLACK_NOTIFY in watch.triggerKinds ||
                AvailabilityTriggerKinds.ATC in watch.triggerKinds
        if (!requiresInternalPolling) return

        val campsites = scopeResolver.resolve(watch)
        if (campsites.isEmpty()) {
            throw AvailabilityWatchValidationException(
                error = UNSUPPORTED_TRIGGER_ERROR,
                message =
                    if (AvailabilityTriggerKinds.ATC in watch.triggerKinds) {
                        ATC_EMPTY_SCOPE_DETAIL
                    } else {
                        WATCH_TRIGGER_EMPTY_SCOPE_DETAIL
                    },
            )
        }

        val pollingSupport = capabilities.internalPollingSupportFor(campsites)
        if (!pollingSupport.supported) {
            throw AvailabilityWatchValidationException(
                error = UNSUPPORTED_TRIGGER_ERROR,
                message =
                    "internal polling is not supported for ${pollingSupport.unsupportedCount} of " +
                        "${pollingSupport.scopedCount} scoped campsite(s)",
            )
        }

        if (AvailabilityTriggerKinds.ATC !in watch.triggerKinds) return

        val bookingSupport = capabilities.bookingSupportFor(BookingAction.ADD_TO_CART, campsites)
        if (!bookingSupport.supported) {
            throw AvailabilityWatchValidationException(
                error = UNSUPPORTED_TRIGGER_ERROR,
                message =
                    "atc is not supported for ${bookingSupport.unsupportedCount} of " +
                        "${bookingSupport.scopedCount} scoped campsite(s)",
            )
        }
    }
}
