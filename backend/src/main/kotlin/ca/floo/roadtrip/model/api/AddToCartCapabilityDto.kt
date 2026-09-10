package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Why the cart is or is not reachable for this scope and this reader, decided
 * backend-side so the client never guesses it from what `trigger_kinds` omits.
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
