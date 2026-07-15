package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget

internal interface BookingProvider {
    val id: BookingProviderId

    /**
     * Source of truth for whether this provider can perform [action] for the
     * concrete [target]. Static registration only routes to the provider; this
     * method owns target-level support.
     */
    fun can(
        action: BookingAction,
        target: BookingTarget,
    ): Boolean

    suspend fun addToCart(request: AddToCartRequest): AddToCartResult
}
