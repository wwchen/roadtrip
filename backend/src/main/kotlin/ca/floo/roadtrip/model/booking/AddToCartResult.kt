package ca.floo.roadtrip.model.booking

import ca.floo.roadtrip.model.domain.provider.BookingProvider
import kotlinx.serialization.json.JsonObject

sealed interface AddToCartResult {
    data class Completed(
        val providerId: BookingProvider,
        val request: JsonObject,
        val response: JsonObject,
    ) : AddToCartResult

    data class Failed(
        val providerId: BookingProvider,
        val error: String,
        val detail: String?,
        val request: JsonObject,
        val response: JsonObject?,
    ) : AddToCartResult

    data object Unsupported : AddToCartResult
}
