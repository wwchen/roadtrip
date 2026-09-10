package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why the cart is or is not reachable for this scope and this reader, decided
 * backend-side so the client never subtracts `booking_actions` from
 * `trigger_kinds` to guess it.
 */
@Serializable
enum class AddToCartState {
    @SerialName("ready")
    READY,

    @SerialName("no_credentials")
    NO_CREDENTIALS,

    @SerialName("signed_out")
    SIGNED_OUT,

    @SerialName("unsupported")
    UNSUPPORTED,
}

@Serializable
data class AddToCartCapabilityDto(
    val state: AddToCartState,
)
