package ca.floo.roadtrip.service.booking

/**
 * What an adapter supports. Surfaced to the FE through
 * `GET /api/campsite/capabilities/{poi_id}` so the drawer can hide UI
 * affordances the upstream can't honor.
 *
 * Conservative defaults: a new adapter answers "no" to every capability
 * until the corresponding interface is implemented. Lying upward — claiming
 * a capability the adapter can't deliver — is the worst failure mode.
 */
data class BookingCapabilities(
    /** Can serve per-day availability for a date window. */
    val supportsAvailability: Boolean,
    /** Can be polled in the background to drive alerts (`AlertEvaluator`). */
    val supportsAlerts: Boolean,
    /** Can add to cart / reserve on the user's behalf (`AutoBooker`). */
    val supportsAutoBook: Boolean,
    /** Max days into the future the upstream exposes (e.g. rec.gov = 180). */
    val bookingHorizonDays: Int,
) {
    companion object {
        /** Reasonable starting point for a stub — can be flipped on as features land. */
        val UNSUPPORTED: BookingCapabilities =
            BookingCapabilities(
                supportsAvailability = false,
                supportsAlerts = false,
                supportsAutoBook = false,
                bookingHorizonDays = 0,
            )
    }
}
