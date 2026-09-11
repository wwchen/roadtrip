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

/**
 * [provider]/[providerDisplay] name the adapter this state is about — an aliased
 * campground is served by one vendor and booked through another, so the POI's
 * `booking_system` is the wrong name for the gate copy. Null when unsupported.
 */
@Serializable
data class AddToCartCapabilityDto(
    val state: AddToCartState,
    val provider: String? = null,
    @SerialName("provider_display") val providerDisplay: String? = null,
)
