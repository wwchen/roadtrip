package ca.floo.roadtrip.fixtures

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.service.booking.BookingAdapter
import kotlinx.serialization.json.JsonObject

/** The cart a fake hold lands in. No real vendor's, which is the point. */
internal const val FAKE_CART_URL = "https://cart.example.test/hold"

/**
 * Configurable [BookingAdapter] stand-in for booking-seam tests.
 *
 * By default it claims its own provider's refs, holds anything with a concrete
 * vendor site id, and can fulfil for every caller. The knobs are the axes tests
 * actually vary: whether the vendor supports holds at all, which callers have
 * credentials with it, and what a hold comes back as. [requests] records every
 * hold that reached it.
 */
internal class FakeBookingAdapter(
    override val id: BookingProvider = BookingProvider.RECGOV,
    private val cartUrl: String = FAKE_CART_URL,
    private val supportsAddToCart: Boolean = true,
    private val credentialed: (UserId) -> Boolean = { true },
    private val result: ((AddToCartRequest) -> AddToCartResult)? = null,
) : BookingAdapter {
    val requests = mutableListOf<AddToCartRequest>()

    override fun canFulfil(user: UserId): Boolean = credentialed(user)

    override fun targetFor(
        parentRef: BookingProviderRef,
        campsiteId: Long,
        vendorSiteId: String,
    ): BookingTarget? {
        if (parentRef.provider != id) return null
        return BookingTarget(
            providerId = id,
            parentRef = parentRef,
            campsiteId = campsiteId,
            vendorSiteId = vendorSiteId,
        )
    }

    override fun can(
        action: BookingAction,
        target: BookingTarget,
    ): Boolean =
        supportsAddToCart &&
            action == BookingAction.ADD_TO_CART &&
            target.providerId == id &&
            target.parentRef.provider == id &&
            target.vendorSiteId.isNotBlank()

    override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
        requests += request
        result?.let { return it(request) }
        if (!can(BookingAction.ADD_TO_CART, request.target)) return AddToCartResult.Unsupported
        return AddToCartResult.Completed(
            providerId = id,
            cartUrl = cartUrl,
            request = JsonObject(emptyMap()),
            response = JsonObject(emptyMap()),
        )
    }
}
