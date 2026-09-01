package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.api.RECGOV_CART_URL
import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import ca.floo.roadtrip.service.settings.RecGovCredentialsConfigured
import org.slf4j.LoggerFactory
import java.time.LocalDate

/** Refusals a caller can act on, kept apart from the codes the companion emits. */
object BookingActionCodes {
    /** Nothing in this scope can be added to a cart by any adapter we have. */
    const val UNSUPPORTED_TARGET = "unsupported_target"

    /** The caller has no rec.gov credentials, so no cart to hold it in. */
    const val CREDENTIALS_REQUIRED = "credentials_required"

    /** The grid the caller clicked is stale, or the companion says it is gone. */
    const val NOT_AVAILABLE = "not_available"

    /** The window was not a positive number of nights. */
    const val INVALID_WINDOW = "invalid_window"
}

/**
 * The two reads this service needs, as its own narrow ports.
 *
 * Not the repo classes themselves: they are final, and opening a whole repo so
 * one test can fake one method is how `AvailabilityWatchRepo` ended up `open`.
 * A service that needs "is this campsite bookable" and "which of these nights
 * currently read available" should say exactly that; the DI module adapts the
 * repos to it in one line each.
 */
internal fun interface BookingCampsiteLookup {
    fun findById(campsiteId: Long): Campsite?
}

internal fun interface CurrentAvailabilityLookup {
    /** Of [nights], the ones currently observed as bookable. */
    fun availableNights(
        campsiteId: Long,
        nights: List<LocalDate>,
    ): Set<LocalDate>
}

/**
 * What a direct add-to-cart came to.
 *
 * A sealed result rather than an exception or an HTTP status: the service does
 * not know it is being called over HTTP, and the route does not know what a
 * companion is. The route maps these onto statuses in one place.
 */
internal sealed interface AddToCartOutcome {
    data class Held(
        val cartUrl: String,
    ) : AddToCartOutcome

    /** A gate refused before the browser was ever driven. */
    data class Refused(
        val code: String,
    ) : AddToCartOutcome

    /** The adapter ran and did not get a hold. [code] is the companion's own. */
    data class Failed(
        val code: String,
        val detail: String?,
    ) : AddToCartOutcome
}

/** Port: the contract the booking route depends on. */
internal interface BookingActionPort {
    suspend fun addToCart(
        caller: UserId,
        campsiteId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AddToCartOutcome
}

/**
 * The user-initiated half of the booking seam.
 *
 * Same adapter, same profile threading and same one-shot re-login as
 * `AtcTriggerActionHandler` — the difference is only who asked and who is
 * listening. A watch fires unattended and reports by email and Slack; this
 * caller is watching a spinner, so the answer is the HTTP response and nothing
 * is sent anywhere.
 *
 * Gates run cheapest-first and each rules out a *different* reason the hold
 * cannot happen, so the caller learns the actual blocker rather than a generic
 * failure after a 30-second browser round trip.
 */
internal class BookingActionService(
    private val campsites: BookingCampsiteLookup,
    private val availabilityTargets: AvailabilityTargetResolver,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val credentials: RecGovCredentialsConfigured,
    private val availability: CurrentAvailabilityLookup,
    private val bookings: BookingAdapterRegistry,
) : BookingActionPort {
    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun addToCart(
        caller: UserId,
        campsiteId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): AddToCartOutcome {
        if (!endDate.isAfter(startDate)) return AddToCartOutcome.Refused(BookingActionCodes.INVALID_WINDOW)

        // 1. Can this campsite be added to a cart at all? Answered by the same
        //    resolver the capability block and the watch validator use, so the
        //    grid never offers a button this would refuse.
        val campsite = campsites.findById(campsiteId) ?: return AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET)
        val resolved = availabilityTargets.resolve(campsite) ?: return AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET)
        val target =
            bookingTargets.targetFor(BookingAction.ADD_TO_CART, resolved)
                ?: return AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET)

        // 2. Does this caller have somewhere to put it? Configured, not proven —
        //    the same gate the `atc` trigger uses.
        if (!credentials.isConfigured(caller)) return AddToCartOutcome.Refused(BookingActionCodes.CREDENTIALS_REQUIRED)

        // 3. Is the grid the caller clicked still true? A read of what the
        //    poller last saw, not a vendor call: this exists to catch a stale
        //    tab cheaply, not to be authoritative. The companion is the
        //    authority and gets the last word below.
        if (!currentlyAvailable(campsiteId, startDate, endDate)) return AddToCartOutcome.Refused(BookingActionCodes.NOT_AVAILABLE)

        val request =
            AddToCartRequest(
                // No watch fired this; a person clicked it.
                watchId = null,
                ownerUserId = caller.value,
                target = target,
                arrivalDate = startDate,
                checkoutDate = endDate,
                campsiteLabel = campsite.name.orEmpty(),
                campgroundId = resolved.campground.id,
                campgroundName = resolved.campground.name,
                // Nothing to stop: there is no watch behind this.
                stopWhenTriggered = false,
            )

        return when (val result = bookings.addToCart(request)) {
            is AddToCartResult.Completed -> {
                log.info("direct ATC held campsite_id={} for user_id={}", campsiteId, caller.value)
                AddToCartOutcome.Held(RECGOV_CART_URL)
            }
            is AddToCartResult.Failed -> {
                log.info(
                    "direct ATC failed campsite_id={} user_id={} error={} detail={}",
                    campsiteId,
                    caller.value,
                    result.error,
                    result.detail,
                )
                AddToCartOutcome.Failed(result.error, result.detail)
            }
            // The registry disagreeing with the resolver means the two are out
            // of step; report it as the same "we cannot book this" the gate does.
            AddToCartResult.Unsupported -> AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET)
        }
    }

    /**
     * Every night in `[startDate, endDate)` currently reads as bookable.
     *
     * A missing cell counts as unavailable: never having observed a night is
     * not evidence that it is free.
     */
    private fun currentlyAvailable(
        campsiteId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Boolean {
        val nights = generateSequence(startDate) { it.plusDays(1) }.takeWhile { it.isBefore(endDate) }.toList()
        return availability.availableNights(campsiteId, nights).containsAll(nights)
    }
}
