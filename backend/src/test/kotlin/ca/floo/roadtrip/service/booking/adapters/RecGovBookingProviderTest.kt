package ca.floo.roadtrip.service.booking.adapters

import ca.floo.roadtrip.models.availability.CatalogCampsiteRef
import ca.floo.roadtrip.models.booking.AddToCartRequest
import ca.floo.roadtrip.models.booking.AddToCartResult
import ca.floo.roadtrip.models.booking.BookingAction
import ca.floo.roadtrip.models.booking.BookingProviderId
import ca.floo.roadtrip.models.booking.BookingTarget
import ca.floo.roadtrip.models.domain.ProviderRef
import ca.floo.roadtrip.service.availability.DispatchCreateInput
import ca.floo.roadtrip.service.availability.DispatchEnqueuer
import ca.floo.roadtrip.service.availability.DispatchQueued
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TEST_WATCH_ID = 42L
private const val TEST_CAMPSITE_ID = 7L
private const val TEST_VENDOR_ID = "site-7"
private const val TEST_DISPATCH_ID = 99L
private const val TEST_NOTIFIED_WAITERS = 1
private const val TEST_RECGOV_VENDOR = "recgov"
private const val TEST_ADD_TO_CART_KIND = "atc"
private const val TEST_ADD_TO_CART_PAYLOAD_VERSION = "atc.recgov.v1"

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

            assertEquals(
                AddToCartResult.Queued(
                    dispatchId = TEST_DISPATCH_ID,
                    providerId = BookingProviderId.RECGOV,
                    notifiedWaiters = TEST_NOTIFIED_WAITERS,
                ),
                result,
            )
            val input = dispatches.input
            assertEquals(TEST_ADD_TO_CART_KIND, input?.kind)
            assertEquals(TEST_RECGOV_VENDOR, input?.vendor)
            assertEquals(TEST_ADD_TO_CART_PAYLOAD_VERSION, input?.payloadVersion)
            assertEquals(TEST_WATCH_ID, input?.watchId)
            assertEquals(true, input?.stopWhenTriggered)
            val payload = input?.payload
            assertEquals(TEST_WATCH_ID.toString(), payload?.get("watch_id")?.jsonPrimitive?.content)
            assertEquals(TEST_RECGOV_VENDOR, payload?.get("vendor")?.jsonPrimitive?.content)
            assertEquals(TEST_ADD_TO_CART_PAYLOAD_VERSION, payload?.get("payload_version")?.jsonPrimitive?.content)
            assertEquals("2026-07-04", payload?.get("start_date")?.jsonPrimitive?.content)
            assertEquals("2026-07-05", payload?.get("end_date")?.jsonPrimitive?.content)
            val opening =
                payload
                    ?.get("openings")
                    ?.jsonArray
                    ?.single()
                    ?.jsonObject
            assertEquals("Site 7", opening?.get("label")?.jsonPrimitive?.content)
            assertEquals(TEST_CAMPSITE_ID.toString(), opening?.get("campsite_id")?.jsonPrimitive?.content)
            assertEquals(TEST_VENDOR_ID, opening?.get("vendor_id")?.jsonPrimitive?.content)
        }

    @Test
    fun `add to cart returns unsupported without calling dispatch for unsupported target`() =
        runBlocking {
            val dispatches = RecordingDispatches()
            val provider = provider(dispatches)

            val result = provider.addToCart(request(recgovTarget(vendorId = "")))

            assertEquals(AddToCartResult.Unsupported, result)
            assertNull(dispatches.input)
        }

    private fun provider(dispatches: RecordingDispatches = RecordingDispatches()): RecGovBookingProvider = RecGovBookingProvider(dispatches)

    private class RecordingDispatches : DispatchEnqueuer {
        var input: DispatchCreateInput? = null

        override suspend fun enqueue(input: DispatchCreateInput): DispatchQueued {
            this.input = input
            return DispatchQueued(
                id = TEST_DISPATCH_ID,
                kind = input.kind,
                vendor = input.vendor,
                payloadVersion = input.payloadVersion,
                expiresAt = Instant.parse("2026-07-14T00:00:30Z"),
                notifiedWaiters = TEST_NOTIFIED_WAITERS,
            )
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
