package ca.floo.roadtrip.service.settings

import ca.floo.roadtrip.model.api.BookingSettingsDto
import ca.floo.roadtrip.model.api.RecgovLoginResponseDto
import ca.floo.roadtrip.model.api.RecgovLoginStatus
import ca.floo.roadtrip.model.api.RecgovRemovedDto
import ca.floo.roadtrip.model.api.RecgovSessionState
import ca.floo.roadtrip.model.api.RecgovStatusDto
import ca.floo.roadtrip.model.api.RecgovVerifyResponseDto
import ca.floo.roadtrip.model.api.UpdateRecgovRequest
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.availability.AvailabilityTriggerKinds
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.security.SecretCipher
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/** How much of the password is safe to show back. Mirrors the Slack token hint. */
private const val PASSWORD_HINT_CHARS = 4

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
        recgovPasswordHint = settings?.recgovPasswordHint.takeIf { configured },
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
) : RecGovCredentialPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The open MFA challenge per user, held here rather than sent to the client.
     *
     * The companion holds the rec.gov prompt page and expires the challenge on
     * its own minutes-scale TTL, so this map is a pointer with a shorter useful
     * life than the process; a lost entry costs one restarted login.
     */
    private val pendingChallenges = ConcurrentHashMap<Long, String>()

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
            passwordHint = password?.takeLast(PASSWORD_HINT_CHARS),
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

    override suspend fun login(userId: UserId): RecgovLoginResponseDto {
        val credentials = requireCredentials(userId)
        val client = companion ?: return companionUnavailableLogin()
        return recordLogin(userId, client.login(profileId(userId), credentials.username, credentials.password))
    }

    override suspend fun completeMfa(
        userId: UserId,
        code: String,
    ): RecgovLoginResponseDto {
        // Taken, not read: a consumed challenge is spent either way, and the
        // companion refuses a replay. Retrying means starting a fresh login.
        val challengeId =
            pendingChallenges.remove(userId.value)
                ?: return failedLogin(RecGovSessionCodes.MFA_CHALLENGE_UNKNOWN, "no login is waiting for a code")
        val client = companion ?: return companionUnavailableLogin()
        return recordLogin(userId, client.completeMfa(profileId(userId), challengeId, code))
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
                passwordHint = null,
                session = RecgovSessionState.NOT_CONFIGURED,
            )
        }
        val health = companion?.health(profileId(userId)) ?: CompanionSessionHealth.Unavailable(null)
        val (session, detail) =
            when (health) {
                is CompanionSessionHealth.Active -> RecgovSessionState.ACTIVE to null
                is CompanionSessionHealth.Inactive -> RecgovSessionState.EXPIRED to health.code
                is CompanionSessionHealth.Unavailable -> RecgovSessionState.COMPANION_UNAVAILABLE to health.detail
            }
        return RecgovStatusDto(
            configured = true,
            username = stored.recgovUsername,
            passwordHint = stored.recgovPasswordHint,
            session = session,
            detail = detail,
        )
    }

    // ── internals ────────────────────────────────────────────────────────────

    private data class Credentials(
        val username: String,
        val password: String,
    )

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
                pendingChallenges.remove(userId.value)
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
    private fun profileId(userId: UserId): String = userId.value.toString()
}
