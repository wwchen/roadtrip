package ca.floo.roadtrip.service.booking.adapters.recgov

import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_WATCH_ID = 42L
private const val TEST_CAMPSITE_ID = 7L
private const val TEST_VENDOR_ID = "site-7"
private const val TEST_DISPATCH_ID = 99L

class RecGovBookingProviderTest {
    @Test
    fun `can add to cart for recgov target with concrete campsite vendor id`() {
        val provider = provider()

        assertTrue(provider.can(BookingAction.ADD_TO_CART, recgovTarget()))
    }

    @Test
    fun `cannot add to cart for wrong provider, wrong parent ref, or missing campsite vendor id`() {
        val provider = provider()

        assertFalse(provider.can(BookingAction.ADD_TO_CART, recgovTarget(providerId = BookingProviderId.ASPIRA)))
        assertFalse(provider.can(BookingAction.ADD_TO_CART, recgovTarget(parentRef = ProviderRef.Aspira(1L, 2L))))
        assertFalse(provider.can(BookingAction.ADD_TO_CART, recgovTarget(vendorId = "")))
    }

    @Test
    fun `add to cart forwards supported request to dispatch port`() =
        runBlocking {
            val dispatches = RecordingDispatches()
            val provider = provider(dispatches)
            val request = request(recgovTarget())

            val result = provider.addToCart(request)

            assertEquals(AddToCartResult.Queued(TEST_DISPATCH_ID, BookingProviderId.RECGOV, notifiedWaiters = 1), result)
            assertEquals(request, dispatches.request)
        }

    @Test
    fun `add to cart returns unsupported without calling dispatch for unsupported target`() =
        runBlocking {
            val dispatches = RecordingDispatches()
            val provider = provider(dispatches)

            val result = provider.addToCart(request(recgovTarget(vendorId = "")))

            assertEquals(AddToCartResult.Unsupported, result)
            assertNull(dispatches.request)
        }

    private fun provider(dispatches: RecordingDispatches = RecordingDispatches()): RecGovBookingProvider = RecGovBookingProvider(dispatches)

    private class RecordingDispatches : RecGovAddToCartDispatchPort {
        var request: AddToCartRequest? = null

        override suspend fun enqueueRecGovAddToCart(request: AddToCartRequest): AddToCartResult {
            this.request = request
            return AddToCartResult.Queued(TEST_DISPATCH_ID, BookingProviderId.RECGOV, notifiedWaiters = 1)
        }
    }

    private fun request(target: BookingTarget): AddToCartRequest =
        AddToCartRequest(
            watchId = TEST_WATCH_ID,
            target = target,
            arrivalDate = LocalDate.parse("2026-07-04"),
            checkoutDate = LocalDate.parse("2026-07-05"),
            campsiteLabel = "Site 7",
            stopWhenTriggered = true,
        )

    private fun recgovTarget(
        providerId: BookingProviderId = BookingProviderId.RECGOV,
        parentRef: ProviderRef = ProviderRef.RecGov("100"),
        vendorId: String = TEST_VENDOR_ID,
    ): BookingTarget =
        BookingTarget(
            providerId = providerId,
            parentRef = parentRef,
            campsiteRef = CatalogCampsiteRef(campsiteId = TEST_CAMPSITE_ID, vendorId = vendorId),
        )
}
