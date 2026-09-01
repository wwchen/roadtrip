package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire vocabulary for [RecgovStatusDto.session]. */
object RecgovSessionState {
    const val NOT_CONFIGURED = "not_configured"
    const val ACTIVE = "active"
    const val EXPIRED = "expired"

    /** The companion could not be asked. Never an error — the row just says so. */
    const val COMPANION_UNAVAILABLE = "companion_unavailable"
}

/** Wire vocabulary for [RecgovLoginResponseDto.status]. */
object RecgovLoginStatus {
    const val OK = "ok"
    const val MFA_REQUIRED = "mfa_required"
    const val FAILED = "failed"
}

/**
 * The stored credentials plus the live session state, from
 * `GET /api/settings/recgov/status`.
 *
 * Its own endpoint rather than a slice of `GET /api/settings`, because it is the
 * one read that talks to the companion and opening Settings must not wait on it.
 */
@Serializable
data class RecgovStatusDto(
    val configured: Boolean,
    val username: String?,
    @SerialName("password_hint") val passwordHint: String?,
    /** One of [RecgovSessionState]. */
    val session: String,
    val detail: String? = null,
)

/** Body of `PUT /api/settings/recgov`. A null password means "leave unchanged". */
@Serializable
data class UpdateRecgovRequest(
    val username: String? = null,
    val password: String? = null,
)

/** Body of `POST /api/settings/recgov/login/mfa`. */
@Serializable
data class RecgovMfaRequest(
    val code: String,
)

/**
 * Outcome of either login phase.
 *
 * A blocked login is a 200 with `status: "failed"` and a code, not an HTTP
 * error: "rec.gov asked for a captcha" is an answer to the question the button
 * asked, and the challenge id has to travel in a typed field either way.
 */
@Serializable
data class RecgovLoginResponseDto(
    /** One of [RecgovLoginStatus]. */
    val status: String,
    @SerialName("challenge_id") val challengeId: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
    val error: String? = null,
    val detail: String? = null,
)

/** Outcome of the dry-run session check. Never places a cart hold. */
@Serializable
data class RecgovVerifyResponseDto(
    val ok: Boolean,
    val error: String? = null,
    val detail: String? = null,
)

/**
 * Outcome of `DELETE /api/settings/recgov`.
 *
 * [strandedAtcWatches] is how many of the owner's active `atc` watches now have
 * no credentials to fire with. They keep the trigger kind and fail loudly on the
 * next fire; nothing is mutated behind the user's back, so the count is reported
 * rather than acted on.
 */
@Serializable
data class RecgovRemovedDto(
    val removed: Boolean,
    @SerialName("stranded_atc_watches") val strandedAtcWatches: Int,
    /** False when the companion could not be reached; the local delete still happened. */
    @SerialName("companion_signed_out") val companionSignedOut: Boolean,
)
