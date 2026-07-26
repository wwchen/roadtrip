package ca.floo.roadtrip.model.auth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The subset of an OIDC provider's `/.well-known/openid-configuration` this app
 * uses. Deserialized with `ignoreUnknownKeys`, so a provider advertising dozens
 * of extra capabilities parses fine.
 *
 * Reading endpoints from discovery rather than hardcoding them is what makes the
 * vendor a config value: point [ca.floo.roadtrip.config.AuthConfig.issuer] at a
 * different provider and the endpoints follow.
 */
@Serializable
data class OidcDiscoveryDto(
    val issuer: String,
    @SerialName("authorization_endpoint") val authorizationEndpoint: String,
    @SerialName("token_endpoint") val tokenEndpoint: String,
    @SerialName("jwks_uri") val jwksUri: String,
    /** RP-initiated logout. Absent on providers that do not support it. */
    @SerialName("end_session_endpoint") val endSessionEndpoint: String? = null,
)
