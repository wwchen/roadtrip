package ca.floo.roadtrip.models.availability

/**
 * What an availability provider supports. Surfaced to the frontend through
 * availability capability surfaces so the drawer can hide UI affordances the
 * upstream can't honor.
 *
 * Conservative defaults: a new provider answers "no" to every capability
 * until the corresponding behavior is implemented. Lying upward — claiming
 * a capability the provider can't deliver — is the worst failure mode.
 */
data class AvailabilityProviderCapabilities(
    /** Can serve per-day availability for a date window. */
    val supportsAvailability: Boolean,
    /** Can be polled in the background by the internal poller to drive watches. */
    val pollableForAlerts: Boolean,
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
                supportsAvailability = false,
                pollableForAlerts = false,
                bookingHorizonDays = 0,
                maxPollWindowDays = 0,
            )
    }
}
