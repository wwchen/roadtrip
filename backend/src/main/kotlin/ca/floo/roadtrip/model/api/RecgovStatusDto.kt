package ca.floo.roadtrip.model.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Wire vocabulary for [RecgovStatusDto.session]. */
object RecgovSessionState {
    const val NOT_CONFIGURED = "not_configured"
    const val ACTIVE = "active"

    /**
     * Credentials are saved but this profile has never been signed in — the
     * companion has no session to have lost. Distinct from [EXPIRED] because
     * the user has not failed at anything yet; they simply have not started.
     */
    const val NOT_LOGGED_IN = "not_logged_in"
    const val EXPIRED = "expired"

    /** The companion answered, but its own health check threw. Not the user's problem. */
    const val CHECK_FAILED = "check_failed"

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
    /** One of [RecgovSessionState]. */
    val session: String,
    val detail: String? = null,
    /**
     * True while a login of this user's is waiting on a verification code.
     *
     * The companion holds the rec.gov prompt page open for minutes, so a panel
     * that remounted (a reload, a reopened modal) can resume the code step
     * instead of orphaning a challenge that still holds the profile's lock.
     */
    @SerialName("mfa_pending") val mfaPending: Boolean = false,
)

/** Body of `PUT /api/settings/recgov`. A null password means "leave unchanged". */
@Serializable
data class UpdateRecgovRequest(
    val username: String? = null,
    val password: String? = null,
) {
    /** Redacted: the generated one would print the password into any log line. */
    override fun toString(): String = "UpdateRecgovRequest(username=$username, password=${redact(password)})"
}

/** Renders a secret's presence without its value. */
internal fun redact(secret: String?): String = if (secret == null) "null" else "***"

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
    /**
     * Whether the browser profile — its Chromium directory and its saved
     * rec.gov cookie jar — was actually erased.
     *
     * Reported separately from [companionSignedOut] because the two can differ and
     * the UI must not imply a full wipe that did not happen: when the companion
     * is unreachable the credentials are gone from the database but the saved
     * session material is still on the companion host.
     */
    @SerialName("profile_destroyed") val profileDestroyed: Boolean,
)
