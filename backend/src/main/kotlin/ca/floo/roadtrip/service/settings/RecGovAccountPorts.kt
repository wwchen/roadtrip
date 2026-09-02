package ca.floo.roadtrip.service.settings

import ca.floo.roadtrip.model.domain.auth.UserId

/**
 * Whether a user has rec.gov credentials stored.
 *
 * *Configured*, never *proven working*: wrong credentials surface at test time
 * in Settings or at fire time in the failure notification, matching the Slack
 * precedent. Capability gating reads this so `atc` is offered only to users who
 * could plausibly fulfil it.
 */
fun interface RecGovCredentialsConfigured {
    fun isConfigured(userId: UserId): Boolean
}

/**
 * The owner's rec.gov browser session, as the ATC fire path needs it.
 *
 * The booking adapter must answer two questions before it drives a cart hold:
 * is this user's companion profile signed in, and — when it is not — can it be
 * recovered without the user present. Both are answered here rather than in the
 * client, because recovery needs the sealed password and credential custody
 * belongs to [RecGovCredentialService].
 */
interface RecGovProfileSessionPort {
    /** The companion's profile id for [userId]. One authority for the mapping. */
    fun profileId(userId: UserId): String

    suspend fun health(userId: UserId): CompanionSessionHealth

    /**
     * Renew the profile's session from its own cookies — no credentials, no
     * login form, no bot wall.
     *
     * This is the cheap recovery and it must be tried before any credential
     * login: rec.gov's JWT lapses in well under an hour, and a headed login the
     * operator did by hand is recoverable this way long after the token itself
     * expired. Going straight to a credential login instead throws away that
     * session and walks into the automated-login wall.
     */
    suspend fun refreshSession(userId: UserId): CompanionActionResult

    /**
     * One unattended re-login with the stored credentials. Fails — never
     * prompts — when rec.gov asks for an MFA code or shows a captcha.
     */
    suspend fun reLogin(userId: UserId): CompanionActionResult
}
