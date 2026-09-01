package ca.floo.roadtrip.service.settings

import ca.floo.roadtrip.model.api.BookingSettingsDto
import ca.floo.roadtrip.model.api.RecgovLoginResponseDto
import ca.floo.roadtrip.model.api.RecgovLoginStatus
import ca.floo.roadtrip.model.api.RecgovRemovedDto
import ca.floo.roadtrip.model.api.RecgovSessionState
import ca.floo.roadtrip.model.api.RecgovStatusDto
import ca.floo.roadtrip.model.api.RecgovVerifyResponseDto
import ca.floo.roadtrip.model.api.UpdateRecgovRequest
import ca.floo.roadtrip.model.api.redact
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.security.SecretCipher
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Codes that mean the companion's held prompt page is gone, so the remembered
 * challenge id points at nothing.
 *
 * Everything NOT in this set is transient — `profile_busy` above all, which is
 * what a *pending* challenge itself provokes when the user presses Test login
 * again: the challenge holds the profile's lock. Forgetting the id there would
 * strand the user until the companion's minutes-scale TTL expired.
 */
private val challengeEndingCodes =
    setOf(
        RecGovSessionCodes.MFA_INVALID,
        RecGovSessionCodes.LOGIN_FAILED,
        RecGovSessionCodes.CAPTCHA_REQUIRED,
        RecGovSessionCodes.MFA_CHALLENGE_UNKNOWN,
        RecGovSessionCodes.MFA_CHALLENGE_EXPIRED,
    )

/**
 * Projects stored settings into the wire shape the settings document carries.
 *
 * Credentials read back as unconfigured when there is no [cipher]: the sealed
 * password cannot be opened without the key, so offering to log in with it would
 * be a lie.
 */
internal fun bookingSettingsDto(
    settings: UserSettingsRepo.Settings?,
    cipher: SecretCipher?,
): BookingSettingsDto {
    val configured = cipher != null && settings?.recgovUsername != null && settings.recgovPasswordCipher != null
    return BookingSettingsDto(
        recgovConfigured = configured,
        recgovUsername = settings?.recgovUsername.takeIf { configured },
    )
}

/**
 * Port: the contract the rec.gov settings routes depend on.
 */
interface RecGovCredentialPort {
    fun save(
        userId: UserId,
        req: UpdateRecgovRequest,
    ): BookingSettingsDto

    suspend fun remove(userId: UserId): RecgovRemovedDto

    suspend fun login(userId: UserId): RecgovLoginResponseDto

    suspend fun completeMfa(
        userId: UserId,
        code: String,
    ): RecgovLoginResponseDto

    suspend fun verify(userId: UserId): RecgovVerifyResponseDto

    suspend fun status(userId: UserId): RecgovStatusDto
}

/**
 * Per-user rec.gov credentials: storage, and the interactive companion flows
 * Settings drives.
 *
 * The backend is the credential custodian. The password is sealed with [cipher]
 * at rest and only ever leaves this class as an argument to a companion login;
 * no response and no log line carries it. A null [cipher] means the encryption
 * key is unconfigured, which disables storage entirely rather than storing
 * anything in the clear.
 *
 * A null [companion] means the companion is not configured for this deployment.
 * That is a degraded state, not a failure: storage still works and the live
 * session simply reports as unavailable.
 */
class RecGovCredentialService(
    private val settingsRepo: UserSettingsRepo,
    private val watchRepo: AvailabilityWatchRepo,
    private val cipher: SecretCipher?,
    private val companion: CompanionSessionPort?,
) : RecGovCredentialPort,
    RecGovCredentialsConfigured,
    RecGovProfileSessionPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The open MFA challenge per user, held here rather than sent to the client.
     *
     * The companion holds the rec.gov prompt page and expires the challenge on
     * its own minutes-scale TTL, so this map is a pointer with a shorter useful
     * life than the process; a lost entry costs one restarted login.
     */
    private val pendingChallenges = ConcurrentHashMap<Long, String>()

    /**
     * Users with a code submission in flight, so exactly one reaches the held
     * page. `add` is the atomic test-and-set; the entry is always released in a
     * `finally`, which is what keeps a sequential retry unblocked.
     */
    private val mfaInFlight = ConcurrentHashMap.newKeySet<Long>()

    override fun save(
        userId: UserId,
        req: UpdateRecgovRequest,
    ): BookingSettingsDto {
        val username = req.username?.trim()
        if (username.isNullOrBlank()) throw SettingsError.InvalidField("username must not be blank")

        val stored = settingsRepo.find(userId)
        val password = req.password?.takeIf { it.isNotBlank() }
        if (password == null && stored?.recgovPasswordCipher == null) {
            throw SettingsError.InvalidField("password is required the first time credentials are saved")
        }

        // Validate before persisting so a rejected save leaves the row untouched.
        val sealed = password?.let { (cipher ?: throw encryptionUnavailable()).seal(it) }
        settingsRepo.saveRecgovCredentials(
            userId = userId,
            username = username,
            passwordCipher = sealed,
        )
        return bookingSettingsDto(settingsRepo.find(userId), cipher)
    }

    /**
     * Clears the stored credentials, then best-effort signs the companion
     * profile out.
     *
     * The local delete happens first and unconditionally: a user removing their
     * password must not be blocked by an unreachable browser service. The
     * response reports how many of their active `atc` watches this strands —
     * those keep the trigger kind and fail loudly on the next fire rather than
     * being mutated behind the user's back.
     */
    override suspend fun remove(userId: UserId): RecgovRemovedDto {
        val stranded = watchRepo.countByTriggerKind(userId.value, WatchStatus.ACTIVE, AvailabilityTriggerKinds.ATC)
        settingsRepo.clearRecgov(userId)
        pendingChallenges.remove(userId.value)

        val signedOut = companion?.logout(profileId(userId)) == CompanionActionResult.Ok
        if (!signedOut) {
            log.info("rec.gov credentials removed for user={} without a companion sign-out", userId.value)
        }
        return RecgovRemovedDto(removed = true, strandedAtcWatches = stranded, companionSignedOut = signedOut)
    }

    /**
     * Test login: **refresh first, credentials only if that cannot help.**
     *
     * A rec.gov JWT lapses in far less than an hour, so a session the operator
     * established by hand looks dead to `health` long before it is beyond
     * saving. `POST /refresh` renews it from the profile's own cookies — an API
     * call, no login form, no bot wall — and that is nearly always what "Test
     * login" should actually do. Reaching for the credential form first threw
     * away a recoverable session and walked into the automated-login wall,
     * which is exactly what was seen live ~30 minutes after a headed login.
     */
    override suspend fun login(userId: UserId): RecgovLoginResponseDto {
        val credentials = requireCredentials(userId)
        val client = companion ?: return companionUnavailableLogin()

        if (client.refresh(profileId(userId)) == CompanionActionResult.Ok) {
            log.info("rec.gov session refreshed without credentials for user={}", userId.value)
            pendingChallenges.remove(userId.value)
            return RecgovLoginResponseDto(status = RecgovLoginStatus.OK)
        }

        return recordLogin(userId, client.login(profileId(userId), credentials.username, credentials.password))
    }

    override suspend fun completeMfa(
        userId: UserId,
        code: String,
    ): RecgovLoginResponseDto {
        // The challenge is READ rather than taken, so the answer can decide
        // whether it is spent — a busy profile or an unreachable companion never
        // reached the held page and must stay retryable. That makes this a
        // check-then-call, so it needs a guard: two tabs that both resumed the
        // pending step would otherwise send two codes at one held browser page.
        //
        // Fail fast rather than queue. The loser is told `profile_busy`, which is
        // what the companion itself answers for concurrent work on one profile,
        // and waiting behind a companion call that may run for its full timeout
        // would park a request for minutes to submit an almost-certainly stale
        // code. Only CONCURRENT submits are excluded; a later retry is untouched.
        if (!mfaInFlight.add(userId.value)) {
            return failedLogin(RecGovSessionCodes.PROFILE_BUSY, "a verification code is already being submitted")
        }
        try {
            val challengeId =
                pendingChallenges[userId.value]
                    ?: return failedLogin(RecGovSessionCodes.MFA_CHALLENGE_UNKNOWN, "no login is waiting for a code")
            val client = companion ?: return companionUnavailableLogin()
            return recordLogin(userId, client.completeMfa(profileId(userId), challengeId, code))
        } finally {
            mfaInFlight.remove(userId.value)
        }
    }

    override suspend fun verify(userId: UserId): RecgovVerifyResponseDto {
        requireCredentials(userId)
        val client =
            companion
                ?: return RecgovVerifyResponseDto(ok = false, error = RecGovSessionCodes.COMPANION_UNAVAILABLE)
        return when (val result = client.verify(profileId(userId))) {
            is CompanionActionResult.Ok -> RecgovVerifyResponseDto(ok = true)
            is CompanionActionResult.Failed -> RecgovVerifyResponseDto(ok = false, error = result.code, detail = result.detail)
        }
    }

    override suspend fun status(userId: UserId): RecgovStatusDto {
        val stored = bookingSettingsDto(settingsRepo.find(userId), cipher)
        if (!stored.recgovConfigured) {
            return RecgovStatusDto(
                configured = false,
                username = null,
                session = RecgovSessionState.NOT_CONFIGURED,
            )
        }
        val health = companion?.health(profileId(userId)) ?: CompanionSessionHealth.Unavailable(null)
        val (session, detail) =
            when (health) {
                is CompanionSessionHealth.Active -> RecgovSessionState.ACTIVE to null
                is CompanionSessionHealth.NeverLoggedIn -> RecgovSessionState.NOT_LOGGED_IN to null
                // The distinction the status row can honestly draw: the profile
                // HAS been logged in (otherwise health says NeverLoggedIn), so
                // this is a lapsed session, and the fix is a person — not the
                // `recgov_login_failed` a doomed automated attempt would report.
                is CompanionSessionHealth.Inactive ->
                    RecgovSessionState.EXPIRED to (health.code ?: RecGovSessionCodes.SESSION_LAPSED)
                is CompanionSessionHealth.CheckFailed -> RecgovSessionState.CHECK_FAILED to health.code
                is CompanionSessionHealth.Unavailable -> RecgovSessionState.COMPANION_UNAVAILABLE to health.detail
            }
        return RecgovStatusDto(
            configured = true,
            username = stored.recgovUsername,
            session = session,
            detail = detail,
            mfaPending = pendingChallenges.containsKey(userId.value),
        )
    }

    // ── ports the watch surfaces and the ATC fire path depend on ─────────────

    override fun isConfigured(userId: UserId): Boolean = bookingSettingsDto(settingsRepo.find(userId), cipher).recgovConfigured

    override suspend fun health(userId: UserId): CompanionSessionHealth =
        companion?.health(profileId(userId)) ?: CompanionSessionHealth.Unavailable(null)

    override suspend fun refreshSession(userId: UserId): CompanionActionResult =
        companion?.refresh(profileId(userId))
            ?: CompanionActionResult.Failed(RecGovSessionCodes.COMPANION_UNAVAILABLE, null)

    /**
     * One unattended login with the stored credentials, for a profile whose
     * session died between the keepalive sweep and the fire.
     *
     * Decryption is [requireCredentials], the same helper the interactive login
     * uses — the sealed password is opened in exactly one place. The companion
     * call is marked `unattended`, so an MFA prompt answers without opening a
     * challenge: nobody is here to read a code, and a challenge would hold the
     * profile's busy lock for its whole TTL.
     *
     * **It must not disturb an interactive challenge.** A user mid-MFA in
     * Settings holds the profile lock, so this call comes back `profile_busy` —
     * transient, and emphatically not a reason to forget the challenge id the
     * user is about to submit a code against. Only the codes that mean the
     * companion's held page is genuinely gone clear it, exactly as [recordLogin]
     * decides.
     */
    override suspend fun reLogin(userId: UserId): CompanionActionResult {
        val credentials =
            try {
                requireCredentials(userId)
            } catch (e: SettingsError) {
                return CompanionActionResult.Failed(RecGovSessionCodes.NOT_CONFIGURED, e.message)
            }
        val client = companion ?: return CompanionActionResult.Failed(RecGovSessionCodes.COMPANION_UNAVAILABLE, null)

        // Cookies before credentials here too: a refresh costs one API call and
        // cannot hit a bot wall, where the credential login can.
        if (client.refresh(profileId(userId)) == CompanionActionResult.Ok) return CompanionActionResult.Ok

        return when (
            val result =
                client.login(
                    profileId = profileId(userId),
                    username = credentials.username,
                    password = credentials.password,
                    unattended = true,
                )
        ) {
            is CompanionLoginResult.Ok -> {
                // The profile is signed in, so whatever page a remembered
                // challenge pointed at is gone. Leaving the id would keep the
                // status row offering a code step that can only fail.
                pendingChallenges.remove(userId.value)
                CompanionActionResult.Ok
            }
            // The companion opens no challenge for an unattended caller, so
            // there is nothing to remember even if it somehow answers one.
            is CompanionLoginResult.MfaRequired -> CompanionActionResult.Failed(RecGovSessionCodes.MFA_REQUIRED, null)
            is CompanionLoginResult.Failed -> {
                if (result.code in challengeEndingCodes) pendingChallenges.remove(userId.value)
                CompanionActionResult.Failed(result.code, result.detail)
            }
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * Not a data class: the generated `toString` would print the plaintext
     * password, and one future log line is all it would take.
     */
    internal class Credentials(
        val username: String,
        val password: String,
    ) {
        override fun toString(): String = "Credentials(username=$username, password=${redact(password)})"
    }

    private fun requireCredentials(userId: UserId): Credentials {
        val c = cipher ?: throw encryptionUnavailable()
        val stored = settingsRepo.find(userId)
        val username = stored?.recgovUsername ?: throw SettingsError.RecgovNotConfigured()
        val sealed = stored.recgovPasswordCipher ?: throw SettingsError.RecgovNotConfigured()
        return Credentials(username, c.open(sealed))
    }

    /** Remembers or forgets the open challenge, then shapes the wire answer. */
    private fun recordLogin(
        userId: UserId,
        result: CompanionLoginResult,
    ): RecgovLoginResponseDto =
        when (result) {
            is CompanionLoginResult.Ok -> {
                pendingChallenges.remove(userId.value)
                RecgovLoginResponseDto(status = RecgovLoginStatus.OK)
            }
            is CompanionLoginResult.MfaRequired -> {
                pendingChallenges[userId.value] = result.challengeId
                RecgovLoginResponseDto(
                    status = RecgovLoginStatus.MFA_REQUIRED,
                    challengeId = result.challengeId,
                    expiresAt = result.expiresAt,
                )
            }
            is CompanionLoginResult.Failed -> {
                if (result.code in challengeEndingCodes) pendingChallenges.remove(userId.value)
                failedLogin(result.code, result.detail)
            }
        }

    private fun failedLogin(
        code: String,
        detail: String?,
    ) = RecgovLoginResponseDto(status = RecgovLoginStatus.FAILED, error = code, detail = detail)

    private fun companionUnavailableLogin() = failedLogin(RecGovSessionCodes.COMPANION_UNAVAILABLE, null)

    private fun encryptionUnavailable() =
        SettingsError.EncryptionUnavailable("Encryption key not configured; cannot store rec.gov credentials")

    /** The companion's profile id for a user is their user id, as an opaque string. */
    override fun profileId(userId: UserId): String = userId.value.toString()
}
