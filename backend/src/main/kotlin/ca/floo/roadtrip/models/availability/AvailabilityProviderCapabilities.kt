package ca.floo.roadtrip.models.availability

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
) {
    companion object {
        /** Reasonable starting point for a stub; can be flipped on as features land. */
        val UNSUPPORTED: AvailabilityProviderCapabilities =
            AvailabilityProviderCapabilities(
                supportsInternalPolling = false,
                bookingHorizonDays = 0,
                maxPollWindowDays = 0,
            )
    }
}
