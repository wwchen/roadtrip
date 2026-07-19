package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
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

    /**
     * Translates provider-specific catalog identity into a booking target this
     * adapter understands. Availability code should not maintain a central
     * BookingProviderRef -> BookingAdapter map; each booking adapter owns that shape.
     */
    fun targetFor(
        parentRef: BookingProviderRef,
        campsiteRef: CatalogCampsiteRef,
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
