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
    /**
     * Human-readable identity-provider name for login UI copy ("Continue
     * with Clerk"). Null when auth is disabled or the provider is unbranded;
     * the frontend then falls back to its generic sign-in copy.
     */
    @SerialName("provider_label") val providerLabel: String? = null,
    /**
     * Whether the active provider uses the in-app embedded login card (Auth0)
     * versus the full-page hosted flow (Clerk and every other vendor). The
     * frontend branches its sign-in affordance on this: true → mount the
     * email/password card, false → redirect to `GET /auth/login`. Defaults
     * false so an unconfigured or hosted provider never offers the Auth0-only
     * embedded form.
     */
    @SerialName("auth_embedded") val isEmbeddedLogin: Boolean = false,
)

@Serializable
data class MeUserDto(
    val id: Long,
    val email: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("email_verified") val isEmailVerified: Boolean,
    val roles: List<String> = emptyList(),
    /**
     * The user's saved appearance preference — one of
     * [ca.floo.roadtrip.service.settings.THEME_VALUES]. Present only here, not
     * on [MeResponseDto] itself, because it is meaningless without a signed-in
     * user: an anonymous caller has nothing saved and follows
     * `prefers-color-scheme` instead. Every page fetches `/api/me` via
     * `useMe()`, so this is what lets a saved theme apply on first paint on a
     * new device, without waiting for the Settings modal to open.
     */
    val theme: String? = null,
)
