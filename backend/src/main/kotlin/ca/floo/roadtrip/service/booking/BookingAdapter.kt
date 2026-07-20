package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.support.Dispatchable

internal interface BookingAdapter : Dispatchable<BookingProvider> {
    val id: BookingProvider

    override fun canHandle(key: BookingProvider): Boolean = key == id

    fun targetFor(
        parentRef: BookingProviderRef,
        campsiteId: Long,
        vendorSiteId: String,
    ): BookingTarget?

    /**
     * Source of truth for whether this adapter can perform [action] for the
     * concrete [target]. Static registration only routes to the adapter; this
     * method owns target-level support.
     */
    fun can(
        action: BookingAction,
        target: BookingTarget,
    ): Boolean

    suspend fun addToCart(request: AddToCartRequest): AddToCartResult
}
