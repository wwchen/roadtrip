package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.models.domain.ProviderRef
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

class BookingProviderRegistryTest {
    @Test
    fun `can delegates target support to the routed provider`() {
        val provider = FakeBookingProvider(canAddToCart = true)
        val registry = BookingProviderRegistry(listOf(provider))

        assertTrue(registry.can(BookingAction.ADD_TO_CART, target()))
    }

    @Test
    fun `can returns false when provider is absent or declines target`() {
        val decliningRegistry = BookingProviderRegistry(listOf(FakeBookingProvider(canAddToCart = false)))
        val emptyRegistry = BookingProviderRegistry(emptyList())

        assertFalse(decliningRegistry.can(BookingAction.ADD_TO_CART, target()))
        assertFalse(emptyRegistry.can(BookingAction.ADD_TO_CART, target()))
    }

    @Test
    fun `target for asks providers to translate provider refs`() {
        val registry = BookingProviderRegistry(listOf(FakeBookingProvider(canAddToCart = true)))

        val target = registry.targetFor(BookingAction.ADD_TO_CART, ProviderRef.RecGov("100"), campsiteRef())

        assertEquals(BookingProviderId.RECGOV, target?.providerId)
        assertEquals(ProviderRef.RecGov("100"), target?.parentRef)
        assertEquals(campsiteRef(), target?.campsiteRef)
    }

    @Test
    fun `add to cart returns unsupported when provider declines target`() =
        runBlocking {
            val provider = FakeBookingProvider(canAddToCart = false)
            val registry = BookingProviderRegistry(listOf(provider))

            val result = registry.addToCart(request())

            assertEquals(AddToCartResult.Unsupported, result)
            assertEquals(0, provider.addToCartCalls)
        }

    @Test
    fun `add to cart delegates to provider when target is supported`() =
        runBlocking {
            val provider = FakeBookingProvider(canAddToCart = true)
            val registry = BookingProviderRegistry(listOf(provider))

            val result = registry.addToCart(request())

            assertEquals(
                AddToCartResult.Completed(
                    providerId = BookingProviderId.RECGOV,
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
            BookingProviderRegistry(listOf(FakeBookingProvider(canAddToCart = true), FakeBookingProvider(canAddToCart = true)))
        }
    }

    private class FakeBookingProvider(
        private val canAddToCart: Boolean,
    ) : BookingProvider {
        var addToCartCalls = 0

        override val id: BookingProviderId = BookingProviderId.RECGOV

        override fun targetFor(
            parentRef: ProviderRef,
            campsiteRef: CatalogCampsiteRef,
        ): BookingTarget? {
            if (parentRef !is ProviderRef.RecGov) return null
            return BookingTarget(
                providerId = id,
                parentRef = parentRef,
                campsiteRef = campsiteRef,
            )
        }

        override fun can(
            action: BookingAction,
            target: BookingTarget,
        ): Boolean = action == BookingAction.ADD_TO_CART && canAddToCart

        override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
            addToCartCalls += 1
            return AddToCartResult.Completed(
                providerId = BookingProviderId.RECGOV,
                request = JsonObject(emptyMap()),
                response = JsonObject(emptyMap()),
            )
        }
    }

    private fun request(): AddToCartRequest =
        AddToCartRequest(
            watchId = TEST_WATCH_ID,
            target = target(),
            arrivalDate = LocalDate.parse("2026-07-04"),
            checkoutDate = LocalDate.parse("2026-07-05"),
            campsiteLabel = "Site 7",
            stopWhenTriggered = true,
        )

    private fun target(): BookingTarget =
        BookingTarget(
            providerId = BookingProviderId.RECGOV,
            parentRef = ProviderRef.RecGov("100"),
            campsiteRef = campsiteRef(),
        )

    private fun campsiteRef(): CatalogCampsiteRef = CatalogCampsiteRef(campsiteId = TEST_CAMPSITE_ID, vendorId = TEST_VENDOR_ID)
}
