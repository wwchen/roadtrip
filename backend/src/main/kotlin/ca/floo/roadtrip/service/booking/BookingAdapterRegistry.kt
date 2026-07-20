package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef

internal class BookingAdapterRegistry(
    adapters: List<BookingAdapter>,
) {
    private val byId = adapters.associateBy { it.id }

    init {
        require(adapters.size == byId.size) {
            "duplicate booking adapters: " +
                adapters.groupBy { it.id }.filterValues { it.size > 1 }.keys
        }
    }

    fun adapterFor(target: BookingTarget): BookingAdapter? = byId[target.providerId]

    fun targetFor(
        action: BookingAction,
        parentRef: BookingProviderRef,
        campsiteId: Long,
        vendorSiteId: String,
    ): BookingTarget? =
        byId.values
            .asSequence()
            .mapNotNull { it.targetFor(parentRef, campsiteId, vendorSiteId) }
            .firstOrNull { can(action, it) }

    fun can(
        action: BookingAction,
        target: BookingTarget,
    ): Boolean = adapterFor(target)?.can(action, target) == true

    suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
        val adapter = adapterFor(request.target) ?: return AddToCartResult.Unsupported
        if (!adapter.can(BookingAction.ADD_TO_CART, request.target)) return AddToCartResult.Unsupported
        return adapter.addToCart(request)
    }
}
