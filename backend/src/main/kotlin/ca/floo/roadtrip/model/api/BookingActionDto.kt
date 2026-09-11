package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire vocabulary for [AddToCartResponseDto.status]. */
object BookingActionStatus {
    const val COMPLETED = "completed"
}

/**
 * A user asking, directly, for one campsite-night range to be held.
 *
 * Dates are the same half-open `[start, end)` window the grid and watches use,
 * so `end` is the checkout day and is never itself held.
 */
@Serializable
data class AddToCartRequestDto(
    @SerialName("campsite_id") val campsiteId: Long,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String,
)

@Serializable
data class AddToCartResponseDto(
    /** [BookingActionStatus.COMPLETED]; a failure is an HTTP error, not a status. */
    val status: String,
    /** Where the site is held — the booking provider's own cart, as it reported it. */
    @SerialName("cart_url") val cartUrl: String,
    /** Whose cart it is: an aliased campground is served by one vendor and booked through another. */
    val provider: String,
)
