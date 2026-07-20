package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_WATCH_ID = 42L
private const val TEST_CAMPSITE_ID = 7L
private const val TEST_VENDOR_ID = "300"
private const val TEST_RECGOV_CAMPGROUND_ID = "232447"

class RecGovBookingAdapterTest {
    @Test
    fun `target for translates recgov refs into booking target`() {
        val provider = provider()

        val target =
            provider.targetFor(
                BookingProviderRef.RecGov(TEST_RECGOV_CAMPGROUND_ID),
                TEST_CAMPSITE_ID,
                TEST_VENDOR_ID,
            )

        assertEquals(BookingProvider.RECGOV, target?.providerId)
        assertEquals(BookingProviderRef.RecGov(TEST_RECGOV_CAMPGROUND_ID), target?.parentRef)
        assertEquals(TEST_CAMPSITE_ID, target?.campsiteId)
        assertEquals(TEST_VENDOR_ID, target?.vendorSiteId)
    }

    @Test
    fun `target for ignores non recgov refs`() {
        val provider = provider()

        val target =
            provider.targetFor(
                BookingProviderRef.Aspira("bc", 1L, 2L, null),
                TEST_CAMPSITE_ID,
                TEST_VENDOR_ID,
            )

        assertNull(target)
    }

    @Test
    fun `can add to cart for recgov target with concrete campsite vendor id`() {
        val provider = provider()

        assertTrue(provider.can(BookingAction.ADD_TO_CART, recgovTarget()))
    }

    @Test
    fun `cannot add to cart for wrong provider, wrong parent ref, or missing campsite vendor id`() {
        val provider = provider()

        assertFalse(provider.can(BookingAction.ADD_TO_CART, recgovTarget(providerId = BookingProvider.ASPIRA)))
        assertFalse(provider.can(BookingAction.ADD_TO_CART, recgovTarget(parentRef = BookingProviderRef.Aspira("bc", 1L, 2L, null))))
        assertFalse(provider.can(BookingAction.ADD_TO_CART, recgovTarget(vendorId = "")))
    }

    @Test
    fun `add to cart forwards supported request to companion executor`() =
        runBlocking {
            val executor = RecordingAtcExecutor(completedOutcome())
            val provider = provider(executor)
            val request = request(recgovTarget())

            val result = provider.addToCart(request)

            val completed = result as AddToCartResult.Completed
            assertEquals(BookingProvider.RECGOV, completed.providerId)
            assertEquals(executor.payload, completed.request)
            val ok =
                completed.response
                    .get("ok")
                    ?.jsonPrimitive
                    ?.content
                    ?.toBoolean()
            assertEquals(true, ok)
            val payload = executor.payload
            assertEquals(setOf("start_date", "end_date", "campsite_id"), payload?.keys)
            assertEquals("2026-07-04", payload?.get("start_date")?.jsonPrimitive?.content)
            assertEquals("2026-07-05", payload?.get("end_date")?.jsonPrimitive?.content)
            assertEquals(TEST_VENDOR_ID, payload?.get("campsite_id")?.jsonPrimitive?.content)
            assertFalse(payload?.containsKey("watch_id") == true)
            assertFalse(payload?.containsKey("payload_version") == true)
            assertFalse(payload?.containsKey("openings") == true)
        }

    @Test
    fun `add to cart uses companion executor when configured`() =
        runBlocking {
            val executor =
                RecordingAtcExecutor(
                    completedOutcome(),
                )
            val provider = provider(executor)
            val request = request(recgovTarget())

            val result = provider.addToCart(request)

            val completed = result as AddToCartResult.Completed
            assertEquals(BookingProvider.RECGOV, completed.providerId)
            assertEquals(executor.payload, completed.request)
            val ok =
                completed.response
                    .get("ok")
                    ?.jsonPrimitive
                    ?.content
                    ?.toBoolean()
            assertEquals(true, ok)
        }

    @Test
    fun `add to cart returns unsupported without calling companion for unsupported target`() =
        runBlocking {
            val executor = RecordingAtcExecutor(completedOutcome())
            val provider = provider(executor)

            val result = provider.addToCart(request(recgovTarget(vendorId = "")))

            assertEquals(AddToCartResult.Unsupported, result)
            assertNull(executor.payload)
        }

    private fun provider(executor: RecordingAtcExecutor = RecordingAtcExecutor(completedOutcome())): RecGovBookingAdapter =
        RecGovBookingAdapter(executor)

    private fun completedOutcome(): RecGovAtcOutcome.Completed =
        RecGovAtcOutcome.Completed(
            response =
                buildJsonObject {
                    put("ok", true)
                    put("cart_added", true)
                },
        )

    private class RecordingAtcExecutor(
        private val outcome: RecGovAtcOutcome,
    ) : RecGovAtcExecutor {
        var payload: JsonObject? = null

        override suspend fun addToCart(payload: JsonObject): RecGovAtcOutcome {
            this.payload = payload
            return outcome
        }
    }

    private fun request(
        target: BookingTarget,
        bookingUrl: String? = null,
    ): AddToCartRequest =
        AddToCartRequest(
            watchId = TEST_WATCH_ID,
            target = target,
            arrivalDate = LocalDate.parse("2026-07-04"),
            checkoutDate = LocalDate.parse("2026-07-05"),
            campsiteLabel = "Site 7",
            bookingUrl = bookingUrl,
            stopWhenTriggered = true,
        )

    private fun recgovTarget(
        providerId: BookingProvider = BookingProvider.RECGOV,
        parentRef: BookingProviderRef = BookingProviderRef.RecGov(TEST_RECGOV_CAMPGROUND_ID),
        vendorId: String = TEST_VENDOR_ID,
    ): BookingTarget =
        BookingTarget(
            providerId = providerId,
            parentRef = parentRef,
            campsiteId = TEST_CAMPSITE_ID,
            vendorSiteId = vendorId,
        )
}
