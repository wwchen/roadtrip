package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.bookingRefFor
import ca.floo.roadtrip.model.domain.provider.BookingAlias
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry

/**
 * Bridges availability resolution to booking resolution.
 *
 * Availability can be served by one provider while booking happens on another —
 * a Campflare catalog row whose sites are actually held on rec.gov is the
 * common case, not an edge one. The rows' own declared identities go first
 * (primary, then each alias, the first adapter claim winning), then the
 * availability candidates, which still matter for providers that derive a
 * booking ref no column carries.
 */
internal class AvailabilityBookingTargetResolver(
    private val bookings: BookingAdapterRegistry,
) {
    fun targetFor(
        action: BookingAction,
        resolved: ResolvedAvailabilityTarget,
    ): BookingTarget? {
        declaredTarget(action, resolved)?.let { return it }

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

    /**
     * The target implied by the rows' own booking identities, primary first. The
     * cart needs the site id on the booking vendor, so each campsite identity is
     * paired with the campground's ref on that same provider. A registered
     * booking adapter treats the identity's ref as the literal vendor site id;
     * aliases are handed over under that same contract.
     */
    private fun declaredTarget(
        action: BookingAction,
        resolved: ResolvedAvailabilityTarget,
    ): BookingTarget? =
        resolved.campsite.bookingIdentities().firstNotNullOfOrNull { identity ->
            val parentRef = resolved.campground.bookingRefFor(identity.provider) ?: return@firstNotNullOfOrNull null
            bookings.targetFor(action, parentRef, resolved.campsite.id, identity.ref)
        }
}

/** This campsite's ids on every provider it names: its primary first, then each alias. */
internal fun Campsite.bookingIdentities(): List<BookingAlias> {
    val provider = bookingProvider?.let(BookingProvider::fromIdOrNull)
    val primary = bookingProviderRef?.takeIf { it.isNotBlank() }
    return buildList {
        provider?.let { p -> primary?.let { add(BookingAlias(p, it)) } }
        bookingAliases.forEach { alias -> if (alias.ref.isNotBlank()) add(alias) }
    }
}
