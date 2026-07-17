package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.Dispatchable

internal interface BookingProvider : Dispatchable<BookingProviderId> {
    val id: BookingProviderId

    override fun canHandle(key: BookingProviderId): Boolean = key == id

    /**
     * Translates provider-specific catalog identity into a booking target this
     * provider understands. Availability code should not maintain a central
     * ProviderRef -> BookingProvider map; each booking adapter owns that shape.
     */
    fun targetFor(
        parentRef: ProviderRef,
        campsiteRef: CatalogCampsiteRef,
    ): BookingTarget?

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
