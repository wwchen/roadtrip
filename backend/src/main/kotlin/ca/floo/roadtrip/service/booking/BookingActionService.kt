package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingFailureCategory
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.metadata.registry.TenantRegistry
import ca.floo.roadtrip.service.availability.AvailabilityBookingTargetResolver
import ca.floo.roadtrip.service.availability.AvailabilityTargetResolver
import org.slf4j.LoggerFactory
import java.time.LocalDate

/** Outcome codes the booking surfaces speak, kept apart from the codes the companion emits. */
object BookingActionCodes {
    /** Nothing in this scope can be added to a cart by any adapter we have. */
    const val UNSUPPORTED_TARGET = "unsupported_target"

    /**
     * The attempt threw before any adapter produced a result.
     *
     * Not a refusal: it names a bug on our side, so the owner-facing copy has to
     * point at us rather than at the vendor or at their credentials. Distinct
     * from the adapter's own `companion_exception`, which means the companion
     * call itself threw — this one is the backend above it.
     */
    const val ATC_EXCEPTION = "atc_exception"

    /** The caller has no credentials with the provider, so no cart to hold it in. */
    const val CREDENTIALS_REQUIRED = "credentials_required"

    /** A recent observation says this site is taken. Not merely unknown. */
    const val NOT_AVAILABLE = "not_available"

    /** The window was not a positive number of nights. */
    const val INVALID_WINDOW = "invalid_window"

    /** The browser reached the vendor and it declined to add the site. */
    const val CART_NOT_ADDED = "cart_not_added"
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
    /** Of [nights], the ones a recent observation says are NOT bookable. */
    fun freshlyUnavailableNights(
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
    /** The vendor slug and the name a person reads, both from the target's ref. */
    data class Held(
        val cartUrl: String,
        val provider: BookingProvider,
        val providerDisplay: String,
    ) : AddToCartOutcome

    /**
     * A gate refused before the browser was ever driven. [provider] is the
     * vendor the refusal is about, and is null only for the gates that run
     * before a target resolves — naming a vendor there would be a guess.
     */
    data class Refused(
        val code: String,
        val provider: BookingProvider? = null,
        val providerDisplay: String? = null,
    ) : AddToCartOutcome

    /**
     * The adapter ran and did not get a hold.
     *
     * [code] is the provider's own, for the sentence the user reads; [category]
     * is the adapter's verdict on who has to act, which is what the route turns
     * into a status. The route never inspects the code.
     */
    data class Failed(
        val code: String,
        val detail: String?,
        val category: BookingFailureCategory,
        val provider: BookingProvider,
        val providerDisplay: String,
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
 * Same adapter and same gates as `AtcTriggerActionHandler` — the difference is
 * only who asked and who is listening. A watch fires unattended and reports by
 * email and Slack; this caller is watching a spinner, so the answer is the HTTP
 * response and nothing is sent anywhere.
 *
 * It names no vendor. Which cart a hold lands in, whether this caller has one,
 * and what a refusal code means are all the adapter's to answer.
 *
 * Gates run cheapest-first and each rules out a *different* reason the hold
 * cannot happen, so the caller learns the actual blocker rather than a generic
 * failure after a 30-second browser round trip.
 */
internal class BookingActionService(
    private val campsites: BookingCampsiteLookup,
    private val availabilityTargets: AvailabilityTargetResolver,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val availability: CurrentAvailabilityLookup,
    private val bookings: BookingAdapterRegistry,
    private val tenants: TenantRegistry,
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
        // The booking site's own name, from the ref the hold would use. Known as
        // soon as a target is, so every refusal from here down can name it.
        val providerDisplay = tenants.displayName(target.parentRef)
        // The registry disagreeing with the resolver means the two are out of
        // step; report it as the same "we cannot book this" the gate does.
        val adapter =
            bookings.adapterFor(target)
                ?: return AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET, target.providerId, providerDisplay)

        // 2. Does this caller have somewhere to put it? The adapter answers for
        //    its own vendor — configured, not proven — and it is the same gate
        //    the `atc` trigger applies.
        if (!adapter.canFulfil(caller)) {
            return AddToCartOutcome.Refused(BookingActionCodes.CREDENTIALS_REQUIRED, adapter.id, providerDisplay)
        }

        // 3. Do we already KNOW this is taken? Only a recent observation
        //    saying "not bookable" stops us here. This is a cheap way to catch
        //    a stale tab, not an authority — so it may only veto on evidence,
        //    never on the absence of it.
        if (knownTaken(campsiteId, startDate, endDate)) {
            return AddToCartOutcome.Refused(BookingActionCodes.NOT_AVAILABLE, adapter.id, providerDisplay)
        }

        val request =
            AddToCartRequest(
                ownerUserId = caller.value,
                target = target,
                arrivalDate = startDate,
                checkoutDate = endDate,
                campsiteLabel = campsite.name.orEmpty(),
                campgroundId = resolved.campground.id,
                campgroundName = resolved.campground.name,
                // Nothing to stop: there is no watch behind this.
                stopWhenTriggered = false,
                // The caller is watching a spinner. Answer now.
                allowUnattendedRelogin = false,
            )

        return when (val result = adapter.addToCart(request)) {
            is AddToCartResult.Completed -> {
                log.info(
                    "direct ATC held campsite_id={} for user_id={} provider={}",
                    campsiteId,
                    caller.value,
                    result.providerId,
                )
                AddToCartOutcome.Held(result.cartUrl, result.providerId, providerDisplay)
            }
            is AddToCartResult.Failed -> {
                log.info(
                    "direct ATC failed campsite_id={} user_id={} error={} detail={}",
                    campsiteId,
                    caller.value,
                    result.error,
                    result.detail,
                )
                AddToCartOutcome.Failed(result.error, result.detail, result.category, result.providerId, providerDisplay)
            }
            // The adapter disowning a target it claimed means the two answers are
            // out of step; the caller hears the same "we cannot book this".
            AddToCartResult.Unsupported ->
                AddToCartOutcome.Refused(BookingActionCodes.UNSUPPORTED_TARGET, adapter.id, providerDisplay)
        }
    }

    /**
     * Some night in `[startDate, endDate)` was recently observed as taken.
     *
     * A missing or stale cell is **not** evidence of anything. The availability
     * table is filled by the watch poller, so a campsite nobody watches simply
     * has no recent rows — treating that silence as "unavailable" refused
     * genuinely bookable sites and made this feature unusable for the
     * browse-then-hold flow it exists for. When we do not know, we ask the
     * vendor, which is the only thing that actually knows.
     */
    private fun knownTaken(
        campsiteId: Long,
        startDate: LocalDate,
        endDate: LocalDate,
    ): Boolean {
        val nights = generateSequence(startDate) { it.plusDays(1) }.takeWhile { it.isBefore(endDate) }.toList()
        return availability.freshlyUnavailableNights(campsiteId, nights).isNotEmpty()
    }
}
