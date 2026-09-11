package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.api.AddToCartCapabilityDto
import ca.floo.roadtrip.model.api.AddToCartState
import ca.floo.roadtrip.model.api.AvailabilityWatchCapabilitiesDto
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.service.booking.BookingAdapter
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry

internal data class WatchCapabilitySupport(
    val scopedCount: Int,
    val unsupportedCount: Int,
) {
    val supported: Boolean get() = scopedCount > 0 && unsupportedCount == 0
}

/**
 * A watch scope with each campsite already resolved to its availability target
 * — null where it has none.
 *
 * Resolving is three DB round trips per campsite, and the capability questions
 * below each need the same answers. Asking them of a scope rather than of a
 * campsite list is what keeps one request to one resolution per campsite.
 */
internal class ResolvedWatchScope(
    val targets: List<ResolvedAvailabilityTarget?>,
)

/**
 * What a proposed watch over this scope could actually do: a cart is a property
 * of the scope, `atc` of the scope and the asker both. `add_to_cart` says which
 * of the two is missing, and names the adapter that would hold the site.
 */
internal class WatchCapabilityService(
    private val availabilityTargets: AvailabilityTargetResolver,
    private val bookingTargets: AvailabilityBookingTargetResolver,
    private val notificationTriggerKinds: List<String> =
        listOf(
            AvailabilityTriggerKinds.SLACK_NOTIFY,
            AvailabilityTriggerKinds.EMAIL_NOTIFY,
        ),
    /** Empty where no booking adapter is wired: `atc` is then never offered. */
    private val bookings: BookingAdapterRegistry = BookingAdapterRegistry(emptyList()),
) {
    fun internalPollingSupportFor(campsites: List<Campsite>): WatchCapabilitySupport = internalPollingSupportFor(resolve(campsites))

    private fun internalPollingSupportFor(scope: ResolvedWatchScope): WatchCapabilitySupport =
        WatchCapabilitySupport(
            scopedCount = scope.targets.size,
            unsupportedCount = scope.targets.count { it?.provider?.capabilities?.supportsInternalPolling != true },
        )

    fun bookingSupportFor(
        action: BookingAction,
        campsites: List<Campsite>,
    ): WatchCapabilitySupport = bookingSupportFor(action, resolve(campsites))

    private fun bookingSupportFor(
        action: BookingAction,
        scope: ResolvedWatchScope,
    ): WatchCapabilitySupport {
        val unsupported =
            scope.targets.count { resolved ->
                resolved == null || bookingTargets.targetFor(action, resolved) == null
            }
        return WatchCapabilitySupport(scopedCount = scope.targets.size, unsupportedCount = unsupported)
    }

    fun supportedBookingActions(campsites: List<Campsite>): Set<BookingAction> = supportedBookingActions(resolve(campsites))

    private fun supportedBookingActions(scope: ResolvedWatchScope): Set<BookingAction> =
        BookingAction.entries
            .filter { bookingSupportFor(it, scope).supported }
            .toSet()

    fun supportedTriggerKinds(
        campsites: List<Campsite>,
        requester: UserId?,
    ): List<String> = resolve(campsites).let { supportedTriggerKinds(it, supportedBookingActions(it), requester) }

    private fun supportedTriggerKinds(
        scope: ResolvedWatchScope,
        bookingActions: Set<BookingAction>,
        requester: UserId?,
    ): List<String> {
        if (!internalPollingSupportFor(scope).supported) return emptyList()
        return buildList {
            addAll(notificationTriggerKinds)
            if (BookingAction.ADD_TO_CART in bookingActions && canFulfilAddToCart(requester, scope)) {
                add(AvailabilityTriggerKinds.ATC)
            }
        }
    }

    /** Whether *this* requester could actually be the account a hold lands in. */
    fun canFulfilAddToCart(
        requester: UserId?,
        campsites: List<Campsite>,
    ): Boolean = canFulfilAddToCart(requester, resolve(campsites))

    /** Resolved-scope overload so a caller answering more than one capability
     *  question about the same scope (write-time validation, `capabilitiesFor`)
     *  resolves it once and shares the result rather than each question
     *  re-walking the campsite list. */
    internal fun canFulfilAddToCart(
        requester: UserId?,
        scope: ResolvedWatchScope,
    ): Boolean {
        val user = requester ?: return false
        val adapters = addToCartAdapters(scope)
        // Every provider in scope, not just the first: a hold on any campsite in
        // it lands in that provider's account, so one missing credential is a
        // scope this requester cannot fulfil.
        return adapters.isNotEmpty() && adapters.all { it.canFulfil(user) }
    }

    /** Whose cart this scope's holds would land in, as a person reads it: the
     *  first adapter [owner] would actually need to add credentials for, not
     *  simply the first adapter in scope — naming an adapter [owner] can
     *  already fulfil would send them to Settings for a provider that was
     *  never the problem. */
    fun addToCartProviderName(
        owner: UserId,
        campsites: List<Campsite>,
    ): String? = addToCartProviderName(owner, resolve(campsites))

    /** Resolved-scope overload; see [canFulfilAddToCart]'s. */
    internal fun addToCartProviderName(
        owner: UserId,
        scope: ResolvedWatchScope,
    ): String? {
        val adapters = addToCartAdapters(scope)
        return (adapters.firstOrNull { !it.canFulfil(owner) } ?: adapters.firstOrNull())?.displayName
    }

    /** The adapters that would hold this scope's sites, each named once. */
    private fun addToCartAdapters(scope: ResolvedWatchScope): List<BookingAdapter> =
        scope.targets
            .mapNotNull { resolved -> resolved?.let { bookingTargets.targetFor(BookingAction.ADD_TO_CART, it) } }
            .mapNotNull(bookings::adapterFor)
            .distinctBy { it.id }

    /**
     * The same question `atc`'s absence answers, but stated: the client renders
     * the reason instead of inferring one from what `trigger_kinds` omits.
     */
    fun addToCartState(
        campsites: List<Campsite>,
        requester: UserId?,
    ): AddToCartState = resolve(campsites).let { addToCartState(it, supportedBookingActions(it), requester) }

    private fun addToCartState(
        scope: ResolvedWatchScope,
        bookingActions: Set<BookingAction>,
        requester: UserId?,
    ): AddToCartState =
        when {
            // A scope that can't be polled can never fire a hold either.
            !internalPollingSupportFor(scope).supported -> AddToCartState.UNSUPPORTED
            BookingAction.ADD_TO_CART !in bookingActions -> AddToCartState.UNSUPPORTED
            requester == null -> AddToCartState.SIGNED_OUT
            !canFulfilAddToCart(requester, scope) -> AddToCartState.NO_CREDENTIALS
            else -> AddToCartState.READY
        }

    fun capabilitiesFor(
        campsites: List<Campsite>,
        requester: UserId?,
    ): AvailabilityWatchCapabilitiesDto {
        // One resolution per campsite for the whole call: the three questions
        // below share it rather than each walking the scope for themselves.
        val scope = resolve(campsites)
        val bookingActions = supportedBookingActions(scope)
        return AvailabilityWatchCapabilitiesDto(
            triggerKinds = supportedTriggerKinds(scope, bookingActions, requester),
            addToCart = AddToCartCapabilityDto(addToCartState(scope, bookingActions, requester)),
        )
    }

    /** Internal, not private: write-time validation resolves a scope once and
     *  shares it across the capability questions it needs answered. */
    internal fun resolve(campsites: List<Campsite>): ResolvedWatchScope = ResolvedWatchScope(campsites.map(availabilityTargets::resolve))
}
