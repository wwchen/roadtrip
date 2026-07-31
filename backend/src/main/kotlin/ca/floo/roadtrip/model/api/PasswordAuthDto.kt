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
)

@Serializable
data class PasswordCompleteRequestDto(
    val code: String,
    val state: String,
)
