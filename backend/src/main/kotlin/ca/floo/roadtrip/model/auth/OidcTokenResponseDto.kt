package ca.floo.roadtrip.model.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Token-endpoint response.
 *
 * Only [idToken] is used. The access token is deliberately ignored: this app
 * calls no provider APIs on the user's behalf, so holding one would be a
 * credential with no purpose — and a thing to leak.
 */
@Serializable
data class OidcTokenResponseDto(
    @SerialName("id_token") val idToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
)
