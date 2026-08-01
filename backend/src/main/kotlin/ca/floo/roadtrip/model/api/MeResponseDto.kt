package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `GET /api/me` — who the caller is.
 *
 * Answers 200 for everyone, signed in or not, with [isAuthenticated] carrying
 * the distinction. A 401 would be wrong: asking who you are is a legitimate
 * question for an anonymous visitor, and the frontend calls this on every page
 * load to decide whether to render a sign-in control. Reserving 401 for genuine
 * authorization failures keeps that signal meaningful.
 */
@Serializable
data class MeResponseDto(
    @SerialName("authenticated") val isAuthenticated: Boolean,
    val user: MeUserDto? = null,
    /**
     * False when no identity provider is configured. The frontend uses this to
     * hide sign-in entirely rather than offer a control that cannot work.
     */
    @SerialName("auth_enabled") val isAuthEnabled: Boolean = true,
    /**
     * Public auth config for the embedded (resource-owner) login flow.
     * Null when auth is disabled. These are non-secret values safe to expose to
     * the browser; secrets (client_secret, PKCE verifier) never leave the backend.
     */
    @SerialName("auth_client_id") val authClientId: String? = null,
    @SerialName("auth_domain") val authDomain: String? = null,
    @SerialName("auth_realm") val authRealm: String? = null,
)

@Serializable
data class MeUserDto(
    val id: Long,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("email_verified") val isEmailVerified: Boolean,
    val roles: List<String> = emptyList(),
)
