package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import ca.floo.roadtrip.support.Dispatchable

/**
 * One booking vendor, and everything vendor-specific about holding a site with
 * it: its cart, its credentials, and its codes.
 *
 * The layers above route to an adapter and repeat what it says. They do not
 * know which vendor answered, so adding a second one is a registration rather
 * than a sweep through the service, the capability gates, and the copy.
 */
internal interface BookingAdapter : Dispatchable<BookingProvider> {
    val id: BookingProvider

    override fun canHandle(key: BookingProvider): Boolean = key == id

    /**
     * Whether [user] has somewhere at this vendor for a hold to land.
     *
     * *Configured*, never *proven working*: wrong credentials surface at test
     * time in Settings or at fire time in the failure notification.
     */
    fun canFulfil(user: UserId): Boolean

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
