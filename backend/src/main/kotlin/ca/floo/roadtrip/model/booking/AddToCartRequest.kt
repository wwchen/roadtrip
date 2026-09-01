package ca.floo.roadtrip.model.booking

import java.time.LocalDate

data class AddToCartRequest(
    /** The watch that fired, or null when a user asked for this hold directly. */
    val watchId: Long?,
    /** The watch owner. The hold lands in *their* cart, never a shared one. */
    val ownerUserId: Long,
    val target: BookingTarget,
    val arrivalDate: LocalDate,
    val checkoutDate: LocalDate,
    val campsiteLabel: String,
    val loop: String? = null,
    val siteType: String? = null,
    val campgroundId: Long? = null,
    val campgroundName: String? = null,
    val bookingUrl: String? = null,
    val stopWhenTriggered: Boolean,
)
