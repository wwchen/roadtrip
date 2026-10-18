package ca.floo.roadtrip.model.availability

/**
 * Optional behavior and limits for an availability provider. Implementing
 * `AvailabilityProvider` is the availability-serving contract; this type only
 * carries the parts that vary per adapter.
 */
data class AvailabilityProviderCapabilities(
    /** Can be polled in the background by the internal poller to drive watches. */
    val supportsInternalPolling: Boolean,
    /** Max days into the future the upstream exposes (e.g. rec.gov = 180). */
    val bookingHorizonDays: Int,
    /**
     * Widest window, in days, the poller asks this vendor for in a single
     * tick. This is a load knob, distinct from [bookingHorizonDays] (how far
     * the upstream exposes): the poller always polls `[today, today +
     * maxPollWindowDays)` — clamped to the horizon — independent of any
     * watch's dates. A watch gates whether a poller runs, never how wide it
     * fetches. Keep it inside a single upstream fetch shape (e.g. rec.gov
     * shapes calls by month) so one tick doesn't fan out into ungoverned
     * sub-calls. Zero means "don't poll" (unsupported stub).
     */
    val maxPollWindowDays: Int,
    /**
     * Whether one call to this vendor covers a campground, whichever of its
     * campsites the caller names. True for a vendor whose response is the
     * campground (rec.gov's facility-months, Campflare's bulk call,
     * ReserveAmerica's park matrix, ReserveCalifornia's facility grids): a caller
     * hands it the whole campground, every campsite's cells get stored, and the
     * next read of any of them needs no call.
     *
     * False for a vendor that answers per part of a campground — Aspira fetches
     * per map, and a campground's campsites sit across sibling maps. Covering the
     * campground there means one call per map, so a caller that only needs some
     * of them pays for the rest. Those callers hand such a vendor the campsites
     * they need, which costs the next, wider read a fetch and is the trade the
     * vendor's shape forces.
     */
    val coversCampgroundPerCall: Boolean = true,
) {
    companion object {
        /** Reasonable starting point for a stub; can be flipped on as features land. */
        val unsupported: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = false,
                bookingHorizonDays = 0,
                maxPollWindowDays = 0,
            )
    }
}
