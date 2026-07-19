package ca.floo.roadtrip.service.booking

import ca.floo.roadtrip.model.availability.CatalogCampsiteRef
import ca.floo.roadtrip.model.booking.AddToCartRequest
import ca.floo.roadtrip.model.booking.AddToCartResult
import ca.floo.roadtrip.model.booking.BookingAction
import ca.floo.roadtrip.model.booking.BookingTarget
import ca.floo.roadtrip.model.domain.provider.BookingProvider
import ca.floo.roadtrip.model.domain.provider.BookingProviderRef
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val ERROR_COMPANION_EXCEPTION = "companion_exception"

internal class RecGovBookingAdapter(
    private val companionAtc: RecGovAtcExecutor,
) : BookingAdapter {
    override val id: BookingProvider = BookingProvider.RECGOV

    override fun targetFor(
        parentRef: BookingProviderRef,
        campsiteRef: CatalogCampsiteRef,
    ): BookingTarget? {
        if (parentRef !is BookingProviderRef.RecGov) return null
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
            target.parentRef is BookingProviderRef.RecGov &&
            target.campsiteRef.vendorId.isNotBlank()

    override suspend fun addToCart(request: AddToCartRequest): AddToCartResult {
        if (!can(BookingAction.ADD_TO_CART, request.target)) return AddToCartResult.Unsupported
        val payload = request.toAtcPayload()
        return request.addToCartViaCompanion(payload)
    }

    private suspend fun AddToCartRequest.addToCartViaCompanion(payload: JsonObject): AddToCartResult =
        when (
            val outcome =
                runCatching { companionAtc.addToCart(payload) }
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

    private fun AddToCartRequest.toAtcPayload(): JsonObject =
        buildJsonObject {
            put("start_date", arrivalDate.toString())
            put("end_date", checkoutDate.toString())
            put("campsite_id", target.campsiteRef.vendorId)
        }
}
