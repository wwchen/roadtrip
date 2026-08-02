package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PasswordBeginRequestDto(
    @SerialName("return_to") val returnTo: String? = null,
)

@Serializable
data class PasswordBeginResponseDto(
    val state: String,
    val nonce: String,
    @SerialName("code_challenge") val codeChallenge: String,
    /** The redirect_uri the backend will use at code-exchange time. The frontend
     *  must pass this exact value to auth0-js so Auth0 sees a matching
     *  redirect_uri in both the authorization request and the token exchange. */
    @SerialName("redirect_uri") val redirectUri: String,
)

@Serializable
data class PasswordCompleteRequestDto(
    val code: String,
    val state: String,
)
