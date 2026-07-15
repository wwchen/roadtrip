package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingTarget

internal class BookingProviderRegistry(
    providers: List<BookingProvider>,
) {
    private val byId = providers.associateBy { it.id }

    init {
        require(providers.size == byId.size) {
            "duplicate booking providers: " +
                providers.groupBy { it.id }.filterValues { it.size > 1 }.keys
        }
    }

    fun providerFor(target: BookingTarget): BookingProvider? = byId[target.providerId]

    fun can(
        action: BookingAction,
        target: BookingTarget,
    ): Boolean = providerFor(target)?.can(action, target) == true

    suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
        val provider = providerFor(request.target) ?: return AddToCartResult.Unsupported
        if (!provider.can(BookingAction.ADD_TO_CART, request.target)) return AddToCartResult.Unsupported
        return provider.addToCart(request)
    }
}
