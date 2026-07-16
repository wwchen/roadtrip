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
import ca.floo.roadtrip.service.availability.dispatchPayloadVersion
import ca.floo.roadtrip.service.availability.provider.adapters.recgov.RecGovBookingUrl
import ca.floo.roadtrip.service.booking.BookingProvider
import ca.floo.roadtrip.service.booking.RecGovAtcExecutor
import ca.floo.roadtrip.service.booking.RecGovAtcOutcome
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

private const val ADD_TO_CART_DISPATCH_KIND = "atc"
private const val RECGOV_VENDOR = "recgov"
private const val ERROR_COMPANION_EXCEPTION = "companion_exception"

private val RECGOV_ADD_TO_CART_PAYLOAD_VERSION = dispatchPayloadVersion(ADD_TO_CART_DISPATCH_KIND, RECGOV_VENDOR)

internal class RecGovBookingProvider(
    private val dispatches: DispatchEnqueuer,
    private val companionAtc: RecGovAtcExecutor? = null,
) : BookingProvider {
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
    ): Boolean =
        action == BookingAction.ADD_TO_CART &&
            target.providerId == id &&
            target.parentRef is ProviderRef.RecGov &&
            target.campsiteRef.vendorId.isNotBlank()

    override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
        if (!can(BookingAction.ADD_TO_CART, request.target)) return AddToCartResult.Unsupported
        val payload = request.toDispatchPayload()
        if (companionAtc != null) {
            return request.addToCartViaCompanion(payload)
        }
        val queued =
            dispatches.enqueue(
                DispatchCreateInput(
                    kind = ADD_TO_CART_DISPATCH_KIND,
                    vendor = RECGOV_VENDOR,
                    payloadVersion = RECGOV_ADD_TO_CART_PAYLOAD_VERSION,
                    payload = payload,
                    watchId = request.watchId,
                    stopWhenTriggered = request.stopWhenTriggered,
                ),
            )
        return AddToCartResult.Queued(
            dispatchId = queued.id,
            providerId = id,
            notifiedWaiters = queued.notifiedWaiters,
        )
    }

    private suspend fun AddToCartRequest.addToCartViaCompanion(payload: JsonObject): AddToCartResult =
        when (
            val outcome =
                runCatching { checkNotNull(companionAtc).addToCart(payload) }
                    .getOrElse { RecGovAtcOutcome.Failed(error = ERROR_COMPANION_EXCEPTION, detail = it.message) }
        ) {
            is RecGovAtcOutcome.Completed ->
                AddToCartResult.Completed(
                    providerId = id,
                    request = payload,
                    response = outcome.response,
                )
            is RecGovAtcOutcome.Failed ->
                AddToCartResult.Failed(
                    providerId = id,
                    error = outcome.error,
                    detail = outcome.detail,
                    request = payload,
                    response = outcome.response,
                )
        }

    private fun AddToCartRequest.toDispatchPayload(): JsonObject =
        buildJsonObject {
            put("watch_id", watchId)
            put("vendor", RECGOV_VENDOR)
            put("payload_version", RECGOV_ADD_TO_CART_PAYLOAD_VERSION)
            put("start_date", arrivalDate.toString())
            put("end_date", checkoutDate.toString())
            putJsonArray("openings") {
                add(toOpeningPayload())
            }
        }

    private fun AddToCartRequest.toOpeningPayload(): JsonObject =
        buildJsonObject {
            put("label", campsiteLabel)
            put("date", arrivalDate.toString())
            put("vendor", RECGOV_VENDOR)
            put("campsite_id", target.campsiteRef.campsiteId)
            put("vendor_id", target.campsiteRef.vendorId)
            target.campsiteRef.mapId?.let { put("map_id", it) }
            target.campsiteRef.resourceLocationId?.let { put("resource_location_id", it) }
            loop?.let { put("loop", it) }
            siteType?.let { put("site_type", it) }
            campgroundId?.let { put("campground_id", it) }
            campgroundName?.let { put("campground", it) }
            put("booking_url", recgovCampsiteBookingUrl())
        }

    private fun AddToCartRequest.recgovCampsiteBookingUrl(): String =
        RecGovBookingUrl.campsite(target.campsiteRef.vendorId, arrivalDate, checkoutDate)
}
