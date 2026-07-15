package ca.floo.roadtrip.service.booking.adapters.recgov

import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.booking.BookingProvider

internal interface RecGovAddToCartDispatchPort {
    suspend fun enqueueRecGovAddToCart(request: AddToCartRequest): AddToCartResult
}

internal class RecGovBookingProvider(
    private val dispatches: RecGovAddToCartDispatchPort,
) : BookingProvider {
    override val id: BookingProviderId = BookingProviderId.RECGOV

    override fun can(
        action: BookingAction,
        target: BookingTarget,
    ): Boolean =
        action == BookingAction.ADD_TO_CART &&
            target.providerId == id &&
            target.parentRef is ProviderRef.RecGov &&
            target.campsiteRef.vendorId.isNotBlank()

    override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
        if (!can(BookingAction.ADD_TO_CART, request.target)) return AddToCartResult.Unsupported
        return dispatches.enqueueRecGovAddToCart(request)
    }
}
