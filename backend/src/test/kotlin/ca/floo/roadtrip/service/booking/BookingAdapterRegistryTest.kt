package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_WATCH_ID = 42L
private const val TEST_CAMPSITE_ID = 7L
private const val TEST_VENDOR_ID = "site-7"

class BookingAdapterRegistryTest {
    @Test
    fun `can delegates target support to the routed provider`() {
        val provider = FakeBookingProvider(canAddToCart = true)
        val registry = BookingAdapterRegistry(listOf(provider))

        assertTrue(registry.can(BookingAction.ADD_TO_CART, target()))
    }

    @Test
    fun `can returns false when provider is absent or declines target`() {
        val decliningRegistry = BookingAdapterRegistry(listOf(FakeBookingProvider(canAddToCart = false)))
        val emptyRegistry = BookingAdapterRegistry(emptyList())

        assertFalse(decliningRegistry.can(BookingAction.ADD_TO_CART, target()))
        assertFalse(emptyRegistry.can(BookingAction.ADD_TO_CART, target()))
    }

    @Test
    fun `target for asks providers to translate provider refs`() {
        val registry = BookingAdapterRegistry(listOf(FakeBookingProvider(canAddToCart = true)))

        val target = registry.targetFor(BookingAction.ADD_TO_CART, BookingProviderRef.RecGov("100"), TEST_CAMPSITE_ID, TEST_VENDOR_ID)

        assertEquals(BookingProvider.RECGOV, target?.providerId)
        assertEquals(BookingProviderRef.RecGov("100"), target?.parentRef)
        assertEquals(TEST_CAMPSITE_ID, target?.campsiteId)
        assertEquals(TEST_VENDOR_ID, target?.vendorSiteId)
    }

    @Test
    fun `add to cart returns unsupported when provider declines target`() =
        runBlocking {
            val provider = FakeBookingProvider(canAddToCart = false)
            val registry = BookingAdapterRegistry(listOf(provider))

            val result = registry.addToCart(request())

            assertEquals(AddToCartResult.Unsupported, result)
            assertEquals(0, provider.addToCartCalls)
        }

    @Test
    fun `add to cart delegates to provider when target is supported`() =
        runBlocking {
            val provider = FakeBookingProvider(canAddToCart = true)
            val registry = BookingAdapterRegistry(listOf(provider))

            val result = registry.addToCart(request())

            assertEquals(
                AddToCartResult.Completed(
                    providerId = BookingProvider.RECGOV,
                    request = JsonObject(emptyMap()),
                    response = JsonObject(emptyMap()),
                ),
                result,
            )
            assertEquals(1, provider.addToCartCalls)
        }

    @Test
    fun `duplicate provider ids are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            BookingAdapterRegistry(listOf(FakeBookingProvider(canAddToCart = true), FakeBookingProvider(canAddToCart = true)))
        }
    }

    private class FakeBookingProvider(
        private val canAddToCart: Boolean,
    ) : BookingAdapter {
        var addToCartCalls = 0

        override val id: BookingProvider = BookingProvider.RECGOV

        override fun targetFor(
            parentRef: BookingProviderRef,
            campsiteId: Long,
            vendorSiteId: String,
        ): BookingTarget? {
            if (parentRef !is BookingProviderRef.RecGov) return null
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
        ): Boolean = action == BookingAction.ADD_TO_CART && canAddToCart

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
            addToCartCalls += 1
            return AddToCartResult.Completed(
                providerId = BookingProvider.RECGOV,
                request = JsonObject(emptyMap()),
                response = JsonObject(emptyMap()),
            )
        }
    }

    private fun request(): AddToCartRequest =
        AddToCartRequest(
            watchId = TEST_WATCH_ID,
            ownerUserId = 1L,
            target = target(),
            arrivalDate = LocalDate.parse("2026-07-04"),
            checkoutDate = LocalDate.parse("2026-07-05"),
            campsiteLabel = "Site 7",
            stopWhenTriggered = true,
        )

    private fun target(): BookingTarget =
        BookingTarget(
            providerId = BookingProvider.RECGOV,
            parentRef = BookingProviderRef.RecGov("100"),
            campsiteId = TEST_CAMPSITE_ID,
            vendorSiteId = TEST_VENDOR_ID,
        )
}
