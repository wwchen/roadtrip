package ca.floo.roadtrip.service.availability

import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.Campground
import ca.floo.roadtrip.model.domain.Campsite
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.booking.BookingAdapterRegistry

/**
 * Bridges availability resolution to booking resolution.
 *
 * Availability can be served by one provider while booking happens on another —
 * a Campflare catalog row whose sites are actually held on rec.gov is the
 * common case, not an edge one. Two sources of a booking identity, in order:
 *
 *  1. **The campground's own declared booking ref** (`booking_provider` +
 *     `booking_provider_ref`). This is the *stated* answer to "where is this
 *     booked", so it is tried first.
 *  2. The availability candidates, each asked to translate the campground into
 *     a ref of its own. This still matters for providers that derive a booking
 *     ref the campground row does not carry.
 *
 * Walking only (2) is what left `booking_actions` empty for POI 8149 "Icicle
 * Group Campground": the Campflare provider answers with a Campflare ref, no
 * booking adapter serves Campflare, and the row's own `recgov/234784` was never
 * consulted.
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
     * The target implied by the campground row's own booking columns.
     *
     * The cart needs the site id *on the booking vendor*, which for a
     * cross-provider row is not the availability catalog's id — Campflare's
     * campsite uuid means nothing to rec.gov. The campsite row carries it in
     * its own `booking_provider`/`booking_provider_ref` pair; when that pair
     * does not name the booking vendor, this site genuinely has no bookable
     * identity and null is the honest answer.
     *
     * **Load-bearing constraint.** This reads the stored ref *raw*, where the
     * candidate walk below goes through [AvailabilityProvider.vendorSiteIdFor]
     * and so picks up per-provider overrides (Aspira derives a resource id
     * rather than using the column). That is sound only while every adapter
     * registered for booking treats its `booking_provider_ref` as the literal
     * vendor site id — true today, where rec.gov is the only one. Register a
     * second adapter whose ref needs deriving and this path would hand it the
     * stored string: give the *booking* seam its own `vendorSiteIdFor` at that
     * point rather than reaching for the availability provider's, which may not
     * even be the same vendor. Until then an unregistered provider's declared
     * ref simply finds no adapter and falls through to the candidate walk,
     * which is what keeps this safe rather than merely lucky.
     */
    private fun declaredTarget(
        action: BookingAction,
        resolved: ResolvedAvailabilityTarget,
    ): BookingTarget? {
        val ref = resolved.campground.declaredBookingRef() ?: return null
        val vendorSiteId = resolved.campsite.bookingRefFor(ref.provider) ?: return null
        return bookings.targetFor(action, ref, resolved.campsite.id, vendorSiteId)
    }
}

/** The typed booking ref a campground row declares, if any. */
internal fun Campground.declaredBookingRef(): BookingProviderRef? {
    val provider = bookingProvider?.let(BookingProvider::fromIdOrNull) ?: return null
    return bookingProviderRef?.let { BookingProviderRef.parse(provider, it) }
}

/** This campsite's id *on [provider]*, when it declares one. */
internal fun Campsite.bookingRefFor(provider: BookingProvider): String? =
    bookingProviderRef?.takeIf { bookingProvider == provider.id && it.isNotBlank() }
