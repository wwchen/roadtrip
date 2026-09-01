package ca.floo.roadtrip.service.settings

/**
 * Outcome codes the rec.gov settings surface speaks.
 *
 * They are the vocabulary shared by the companion adapter, this service and the
 * frontend's `settings-errors.ts`; the companion's own internal blockers stay
 * behind the port (see [ca.floo.roadtrip.client.companion.CompanionSessionClient]).
 */
object RecGovSessionCodes {
    const val LOGIN_FAILED = "login_failed"
    const val MFA_REQUIRED = "mfa_required"
    const val MFA_INVALID = "mfa_invalid"

    /** No challenge is in flight for this user — the login has to start again. */
    const val MFA_CHALLENGE_UNKNOWN = "mfa_challenge_unknown"

    /** The companion's held prompt page timed out and was closed. */
    const val MFA_CHALLENGE_EXPIRED = "mfa_challenge_expired"

    /** Another operation holds this profile's lock. Transient by nature. */
    const val PROFILE_BUSY = "profile_busy"

    /** The companion is at its concurrent-browser cap. Transient by nature. */
    const val BROWSER_CAP_REACHED = "browser_cap_reached"
    const val CAPTCHA_REQUIRED = "captcha_required"
    const val LOGIN_BACKOFF = "login_backoff"
    const val NOT_AUTHENTICATED = "recgov_not_authenticated"
    const val COMPANION_UNAVAILABLE = "companion_unavailable"

    /** No credentials are stored for this user, so nothing can be attempted. */
    const val NOT_CONFIGURED = "recgov_not_configured"
}

/** Result of a credential login or of completing an MFA challenge. */
sealed interface CompanionLoginResult {
    data object Ok : CompanionLoginResult

    /** Rec.gov asked for a code; the companion holds the prompt open until [expiresAt]. */
    data class MfaRequired(
        val challengeId: String,
        val expiresAt: String?,
    ) : CompanionLoginResult

    data class Failed(
        val code: String,
        val detail: String? = null,
    ) : CompanionLoginResult
}

/** Result of a companion call with nothing to report but success or a reason. */
sealed interface CompanionActionResult {
    data object Ok : CompanionActionResult

    data class Failed(
        val code: String,
        val detail: String? = null,
    ) : CompanionActionResult
}

/** What the companion says about one profile's live rec.gov session. */
sealed interface CompanionSessionHealth {
    data object Active : CompanionSessionHealth

    data class Inactive(
        val code: String?,
    ) : CompanionSessionHealth

    /** The companion is unreachable or not configured — never an error to the user. */
    data class Unavailable(
        val detail: String?,
    ) : CompanionSessionHealth
}

/**
 * Port over the companion's per-profile session routes.
 *
 * `profileId` is the caller's own user id, stringified: the backend authenticates
 * the user and only ever passes *their* profile, which is what makes per-user
 * isolation enforceable (see `docs/companion.md`).
 */
interface CompanionSessionPort {
    /**
     * [unattended] tells the companion nobody is waiting on this login, so an
     * MFA prompt must answer without opening a challenge — a challenge holds
     * the profile's busy lock for its whole TTL, and the fire path can never
     * complete one. Only the fire-time re-login sets it.
     */
    suspend fun login(
        profileId: String,
        username: String,
        password: String,
        unattended: Boolean = false,
    ): CompanionLoginResult

    suspend fun completeMfa(
        profileId: String,
        challengeId: String,
        code: String,
    ): CompanionLoginResult

    suspend fun logout(profileId: String): CompanionActionResult

    suspend fun verify(profileId: String): CompanionActionResult

    suspend fun health(profileId: String): CompanionSessionHealth

    /** Force one profile's rec.gov session to renew. The keepalive path. */
    suspend fun refresh(profileId: String): CompanionActionResult

    /**
     * Replaces the armed profile set the companion keeps warm.
     *
     * Wholesale, not a merge: pausing or deleting the last `atc` watch for a
     * user has to actually disarm their profile. An empty set disarms everyone.
     */
    suspend fun markKeepWarm(profileIds: Collection<String>): CompanionActionResult
}
