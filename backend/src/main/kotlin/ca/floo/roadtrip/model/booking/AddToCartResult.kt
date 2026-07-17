package ca.floo.roadtrip.model.booking

import kotlinx.serialization.json.JsonObject

sealed interface AddToCartResult {
    data class Completed(
        val providerId: BookingProviderId,
        val request: JsonObject,
        val response: JsonObject,
    ) : AddToCartResult

    data class Failed(
        val providerId: BookingProviderId,
        val error: String,
        val detail: String?,
        val request: JsonObject,
        val response: JsonObject?,
    ) : AddToCartResult

    data object Unsupported : AddToCartResult
}
