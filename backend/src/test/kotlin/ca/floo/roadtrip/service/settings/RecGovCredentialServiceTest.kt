package ca.floo.roadtrip.service.settings

import ca.floo.roadtrip.model.api.RecgovLoginStatus
import ca.floo.roadtrip.model.api.RecgovSessionState
import ca.floo.roadtrip.model.api.UpdateRecgovRequest
import ca.floo.roadtrip.model.domain.auth.UserId
import ca.floo.roadtrip.repo.AvailabilityWatchRepo
import ca.floo.roadtrip.repo.UserSettingsRepo
import ca.floo.roadtrip.service.availability.WatchStatus
import ca.floo.roadtrip.service.security.SecretCipher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val detachedCtx = DSL.using(SQLDialect.POSTGRES)
private val testUserId = UserId(7L)
private val testKey = ByteArray(32) { it.toByte() }
private const val PROFILE_ID = "7"

/**
 * Ceiling for the tests that park a caller inside the fake companion. Without it
 * a regression in the concurrency guard hangs the build instead of failing it:
 * the second caller would park on the same gate as the first, and nothing would
 * ever complete it.
 */
private const val GATED_TEST_TIMEOUT_MS = 5_000L

private class FakeSettingsRepo : UserSettingsRepo(ctx = detachedCtx) {
    var settings: Settings? = null

    override fun find(userId: UserId): Settings? = settings

    override fun saveRecgovCredentials(
        userId: UserId,
        username: String,
        passwordCipher: ByteArray?,
    ) {
        val current = settings ?: Settings(null, null, null, null)
        settings =
            current.copy(
                recgovUsername = username,
                recgovPasswordCipher = passwordCipher ?: current.recgovPasswordCipher,
            )
    }

    override fun clearRecgov(userId: UserId) {
        settings = settings?.copy(recgovUsername = null, recgovPasswordCipher = null)
    }
}

private class FakeWatchRepo(
    private val activeAtc: Int,
) : AvailabilityWatchRepo(ctx = detachedCtx) {
    override fun countByTriggerKind(
        ownerUserId: Long,
        status: WatchStatus,
        triggerKind: String,
    ): Int = activeAtc
}

private class FakeCompanion(
    var loginResult: CompanionLoginResult = CompanionLoginResult.Ok,
    var mfaResult: CompanionLoginResult = CompanionLoginResult.Ok,
    var verifyResult: CompanionActionResult = CompanionActionResult.Ok,
    var logoutResult: CompanionActionResult = CompanionActionResult.Ok,
    var healthResult: CompanionSessionHealth = CompanionSessionHealth.Active,
) : CompanionSessionPort {
    val loginCalls = mutableListOf<Triple<String, String, String>>()
    val mfaCalls = mutableListOf<Triple<String, String, String>>()
    val logoutCalls = mutableListOf<String>()

    /** When set, [completeMfa] parks inside the companion until it completes. */
    var mfaGate: CompletableDeferred<Unit>? = null

    override suspend fun login(
        profileId: String,
        username: String,
        password: String,
    ): CompanionLoginResult {
        loginCalls += Triple(profileId, username, password)
        return loginResult
    }

    override suspend fun completeMfa(
        profileId: String,
        challengeId: String,
        code: String,
    ): CompanionLoginResult {
        mfaCalls += Triple(profileId, challengeId, code)
        mfaGate?.await()
        return mfaResult
    }

    override suspend fun logout(profileId: String): CompanionActionResult {
        logoutCalls += profileId
        return logoutResult
    }

    override suspend fun verify(profileId: String): CompanionActionResult = verifyResult

    override suspend fun health(profileId: String): CompanionSessionHealth = healthResult
}

class RecGovCredentialServiceTest {
    private val cipher = SecretCipher(testKey)

    private fun service(
        repo: FakeSettingsRepo = FakeSettingsRepo(),
        companion: CompanionSessionPort? = FakeCompanion(),
        withCipher: SecretCipher? = cipher,
        activeAtcWatches: Int = 0,
    ) = RecGovCredentialService(
        settingsRepo = repo,
        watchRepo = FakeWatchRepo(activeAtcWatches),
        cipher = withCipher,
        companion = companion,
    )

    private fun configuredRepo(password: String = "hunter2-secret"): FakeSettingsRepo =
        FakeSettingsRepo().also {
            it.settings =
                UserSettingsRepo.Settings(
                    notificationEmail = null,
                    slackChannel = null,
                    slackTokenCipher = null,
                    slackTokenHint = null,
                    recgovUsername = "ada@example.com",
                    recgovPasswordCipher = cipher.seal(password),
                )
        }

    // ── storage ──────────────────────────────────────────────────────────────

    @Test
    fun `saving seals the password and keeps only a last-4 hint`() {
        val repo = FakeSettingsRepo()

        val dto = service(repo).save(testUserId, UpdateRecgovRequest("ada@example.com", "hunter2-secret"))

        assertTrue(dto.recgovConfigured)
        assertEquals("ada@example.com", dto.recgovUsername)
        assertEquals("hunter2-secret", cipher.open(repo.settings!!.recgovPasswordCipher!!))
    }

    @Test
    fun `a null password leaves the stored one untouched`() {
        val repo = configuredRepo()

        val dto = service(repo).save(testUserId, UpdateRecgovRequest("grace@example.com", null))

        assertEquals("grace@example.com", dto.recgovUsername)
        assertEquals("hunter2-secret", cipher.open(repo.settings!!.recgovPasswordCipher!!))
    }

    @Test
    fun `a blank username is rejected before anything is written`() {
        val repo = FakeSettingsRepo()

        assertFailsWith<SettingsError.InvalidField> {
            service(repo).save(testUserId, UpdateRecgovRequest("  ", "hunter2"))
        }
        assertNull(repo.settings)
    }

    @Test
    fun `a first save without a password is rejected`() {
        assertFailsWith<SettingsError.InvalidField> {
            service().save(testUserId, UpdateRecgovRequest("ada@example.com", null))
        }
    }

    @Test
    fun `storing a password needs the encryption key`() {
        assertFailsWith<SettingsError.EncryptionUnavailable> {
            service(withCipher = null).save(testUserId, UpdateRecgovRequest("ada@example.com", "hunter2"))
        }
    }

    // ── removal ──────────────────────────────────────────────────────────────

    @Test
    fun `removal clears the columns and reports the stranded active atc watches`() =
        runBlocking {
            val repo = configuredRepo()
            val companion = FakeCompanion()

            val dto = service(repo, companion, activeAtcWatches = 3).remove(testUserId)

            assertTrue(dto.removed)
            assertEquals(3, dto.strandedAtcWatches)
            assertTrue(dto.companionSignedOut)
            assertEquals(listOf(PROFILE_ID), companion.logoutCalls)
            assertNull(repo.settings!!.recgovUsername)
            assertNull(repo.settings!!.recgovPasswordCipher)
        }

    @Test
    fun `removal succeeds locally when the companion is down`() =
        runBlocking {
            val repo = configuredRepo()
            val companion =
                FakeCompanion(
                    logoutResult = CompanionActionResult.Failed(RecGovSessionCodes.COMPANION_UNAVAILABLE, "refused"),
                )

            val dto = service(repo, companion).remove(testUserId)

            assertTrue(dto.removed)
            assertFalse(dto.companionSignedOut)
            assertNull(repo.settings!!.recgovPasswordCipher)
        }

    @Test
    fun `removal succeeds with no companion configured at all`() =
        runBlocking {
            val repo = configuredRepo()

            val dto = service(repo, companion = null).remove(testUserId)

            assertTrue(dto.removed)
            assertFalse(dto.companionSignedOut)
            assertNull(repo.settings!!.recgovUsername)
        }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    fun `login uses the saved credentials, decrypted`() =
        runBlocking {
            val companion = FakeCompanion()

            val dto = service(configuredRepo(), companion).login(testUserId)

            assertEquals(RecgovLoginStatus.OK, dto.status)
            assertEquals(listOf(Triple(PROFILE_ID, "ada@example.com", "hunter2-secret")), companion.loginCalls)
        }

    @Test
    fun `login without stored credentials is refused before calling the companion`() =
        runBlocking {
            val companion = FakeCompanion()

            assertFailsWith<SettingsError.RecgovNotConfigured> {
                service(FakeSettingsRepo(), companion).login(testUserId)
            }
            assertTrue(companion.loginCalls.isEmpty())
        }

    @Test
    fun `an MFA prompt is reported with its challenge id`() =
        runBlocking {
            val companion = FakeCompanion(loginResult = CompanionLoginResult.MfaRequired("chal-1", "2026-08-31T00:05:00Z"))

            val dto = service(configuredRepo(), companion).login(testUserId)

            assertEquals(RecgovLoginStatus.MFA_REQUIRED, dto.status)
            assertEquals("chal-1", dto.challengeId)
            assertEquals("2026-08-31T00:05:00Z", dto.expiresAt)
        }

    @Test
    fun `a captcha is reported as a failed login, not an HTTP error`() =
        runBlocking {
            val companion =
                FakeCompanion(loginResult = CompanionLoginResult.Failed(RecGovSessionCodes.CAPTCHA_REQUIRED, "challenge shown"))

            val dto = service(configuredRepo(), companion).login(testUserId)

            assertEquals(RecgovLoginStatus.FAILED, dto.status)
            assertEquals(RecGovSessionCodes.CAPTCHA_REQUIRED, dto.error)
        }

    @Test
    fun `login reports companion_unavailable when no companion is configured`() =
        runBlocking {
            val dto = service(configuredRepo(), companion = null).login(testUserId)

            assertEquals(RecgovLoginStatus.FAILED, dto.status)
            assertEquals(RecGovSessionCodes.COMPANION_UNAVAILABLE, dto.error)
        }

    // ── MFA ──────────────────────────────────────────────────────────────────

    @Test
    fun `the code completes the challenge the login opened`() =
        runBlocking {
            val companion = FakeCompanion(loginResult = CompanionLoginResult.MfaRequired("chal-1", null))
            val svc = service(configuredRepo(), companion)
            svc.login(testUserId)

            val dto = svc.completeMfa(testUserId, "123456")

            assertEquals(RecgovLoginStatus.OK, dto.status)
            assertEquals(listOf(Triple(PROFILE_ID, "chal-1", "123456")), companion.mfaCalls)
        }

    @Test
    fun `a code with no challenge in flight is refused without calling the companion`() =
        runBlocking {
            val companion = FakeCompanion()

            val dto = service(configuredRepo(), companion).completeMfa(testUserId, "123456")

            assertEquals(RecgovLoginStatus.FAILED, dto.status)
            assertEquals(RecGovSessionCodes.MFA_CHALLENGE_UNKNOWN, dto.error)
            assertTrue(companion.mfaCalls.isEmpty())
        }

    @Test
    fun `a transient failure does not destroy the challenge the companion is still holding`() =
        runBlocking {
            // The trap: a pending challenge holds the profile's busy lock, so a
            // second Test login answers 409. Forgetting the id there would lock the
            // user out until the companion's minutes-scale TTL expires.
            val companion = FakeCompanion(loginResult = CompanionLoginResult.MfaRequired("chal-1", null))
            val svc = service(configuredRepo(), companion)
            svc.login(testUserId)

            companion.loginResult = CompanionLoginResult.Failed(RecGovSessionCodes.PROFILE_BUSY, "mfa pending")
            assertEquals(RecGovSessionCodes.PROFILE_BUSY, svc.login(testUserId).error)

            companion.mfaResult = CompanionLoginResult.Ok
            assertEquals(RecgovLoginStatus.OK, svc.completeMfa(testUserId, "123456").status)
            assertEquals(listOf(Triple(PROFILE_ID, "chal-1", "123456")), companion.mfaCalls)
        }

    @Test
    fun `a transient failure while submitting the code keeps the challenge for a retry`() =
        runBlocking {
            val companion =
                FakeCompanion(
                    loginResult = CompanionLoginResult.MfaRequired("chal-1", null),
                    mfaResult = CompanionLoginResult.Failed(RecGovSessionCodes.COMPANION_UNAVAILABLE, "refused"),
                )
            val svc = service(configuredRepo(), companion)
            svc.login(testUserId)

            assertEquals(RecGovSessionCodes.COMPANION_UNAVAILABLE, svc.completeMfa(testUserId, "123456").error)

            companion.mfaResult = CompanionLoginResult.Ok
            assertEquals(RecgovLoginStatus.OK, svc.completeMfa(testUserId, "123456").status)
            assertEquals(2, companion.mfaCalls.size)
        }

    @Test
    fun `a second concurrent code submission is refused rather than racing the first`() =
        runBlocking {
            // Two tabs that both resumed the pending step would otherwise read the
            // same challenge id and send two codes at one held browser page.
            val companion = FakeCompanion(loginResult = CompanionLoginResult.MfaRequired("chal-1", null))
            val svc = service(configuredRepo(), companion)
            svc.login(testUserId)

            val gate = CompletableDeferred<Unit>()
            companion.mfaGate = gate
            withTimeout(GATED_TEST_TIMEOUT_MS) {
                // UNDISPATCHED so the first call is provably parked inside the
                // companion before the second one starts — no polling, no timing.
                val first = async(start = CoroutineStart.UNDISPATCHED) { svc.completeMfa(testUserId, "111111") }
                assertEquals(1, companion.mfaCalls.size)

                val second = svc.completeMfa(testUserId, "222222")

                assertEquals(RecgovLoginStatus.FAILED, second.status)
                assertEquals(RecGovSessionCodes.PROFILE_BUSY, second.error)
                assertEquals(1, companion.mfaCalls.size)

                gate.complete(Unit)
                assertEquals(RecgovLoginStatus.OK, first.await().status)
                assertEquals(listOf(Triple(PROFILE_ID, "chal-1", "111111")), companion.mfaCalls)
            }
        }

    @Test
    fun `the concurrent refusal leaves the challenge standing for a later retry`() =
        runBlocking {
            val companion =
                FakeCompanion(
                    loginResult = CompanionLoginResult.MfaRequired("chal-1", null),
                    mfaResult = CompanionLoginResult.Failed(RecGovSessionCodes.COMPANION_UNAVAILABLE, "refused"),
                )
            val svc = service(configuredRepo(), companion)
            svc.login(testUserId)

            val gate = CompletableDeferred<Unit>()
            companion.mfaGate = gate
            withTimeout(GATED_TEST_TIMEOUT_MS) {
                val first = async(start = CoroutineStart.UNDISPATCHED) { svc.completeMfa(testUserId, "111111") }
                assertEquals(RecGovSessionCodes.PROFILE_BUSY, svc.completeMfa(testUserId, "222222").error)
                gate.complete(Unit)
                assertEquals(RecGovSessionCodes.COMPANION_UNAVAILABLE, first.await().error)
            }

            // Neither the concurrent refusal nor the transient answer spent it.
            companion.mfaGate = null
            companion.mfaResult = CompanionLoginResult.Ok
            assertEquals(RecgovLoginStatus.OK, svc.completeMfa(testUserId, "333333").status)
            assertEquals(2, companion.mfaCalls.size)
        }

    @Test
    fun `the in-flight guard is released so a sequential retry is never blocked`() =
        runBlocking {
            val companion =
                FakeCompanion(
                    loginResult = CompanionLoginResult.MfaRequired("chal-1", null),
                    mfaResult = CompanionLoginResult.Failed(RecGovSessionCodes.PROFILE_BUSY, "companion busy"),
                )
            val svc = service(configuredRepo(), companion)
            svc.login(testUserId)

            repeat(3) { assertEquals(RecGovSessionCodes.PROFILE_BUSY, svc.completeMfa(testUserId, "123456").error) }

            // Every attempt reached the companion: the guard is per-call, not sticky.
            assertEquals(3, companion.mfaCalls.size)
        }

    @Test
    fun `a dead-challenge code clears it`() =
        runBlocking {
            val companion = FakeCompanion(loginResult = CompanionLoginResult.MfaRequired("chal-1", null))
            val svc = service(configuredRepo(), companion)
            svc.login(testUserId)

            // A captcha means rec.gov abandoned the whole attempt, prompt included.
            companion.loginResult = CompanionLoginResult.Failed(RecGovSessionCodes.CAPTCHA_REQUIRED, null)
            svc.login(testUserId)

            assertEquals(RecGovSessionCodes.MFA_CHALLENGE_UNKNOWN, svc.completeMfa(testUserId, "123456").error)
            assertTrue(companion.mfaCalls.isEmpty())
        }

    @Test
    fun `a rejected code clears the challenge so the next attempt starts a fresh login`() =
        runBlocking {
            val companion =
                FakeCompanion(
                    loginResult = CompanionLoginResult.MfaRequired("chal-1", null),
                    mfaResult = CompanionLoginResult.Failed(RecGovSessionCodes.MFA_INVALID, "rejected"),
                )
            val svc = service(configuredRepo(), companion)
            svc.login(testUserId)

            assertEquals(RecGovSessionCodes.MFA_INVALID, svc.completeMfa(testUserId, "000000").error)
            assertEquals(RecGovSessionCodes.MFA_CHALLENGE_UNKNOWN, svc.completeMfa(testUserId, "000001").error)
        }

    // ── verify ───────────────────────────────────────────────────────────────

    @Test
    fun `verify reports a live session`() =
        runBlocking {
            val dto = service(configuredRepo()).verify(testUserId)

            assertTrue(dto.ok)
            assertNull(dto.error)
        }

    @Test
    fun `verify reports the companion's failure code`() =
        runBlocking {
            val companion =
                FakeCompanion(verifyResult = CompanionActionResult.Failed(RecGovSessionCodes.NOT_AUTHENTICATED, "gone"))

            val dto = service(configuredRepo(), companion).verify(testUserId)

            assertFalse(dto.ok)
            assertEquals(RecGovSessionCodes.NOT_AUTHENTICATED, dto.error)
        }

    // ── status ───────────────────────────────────────────────────────────────

    @Test
    fun `status is not_configured with nothing stored, and never asks the companion`() =
        runBlocking {
            val companion = FakeCompanion(healthResult = CompanionSessionHealth.Active)

            val dto = service(FakeSettingsRepo(), companion).status(testUserId)

            assertFalse(dto.configured)
            assertNull(dto.username)
            assertEquals(RecgovSessionState.NOT_CONFIGURED, dto.session)
        }

    @Test
    fun `status reports the stored username, the hint and a live session`() =
        runBlocking {
            val dto = service(configuredRepo()).status(testUserId)

            assertTrue(dto.configured)
            assertEquals("ada@example.com", dto.username)
            assertEquals(RecgovSessionState.ACTIVE, dto.session)
        }

    @Test
    fun `status reports an expired session with the companion's reason`() =
        runBlocking {
            val companion = FakeCompanion(healthResult = CompanionSessionHealth.Inactive(RecGovSessionCodes.NOT_AUTHENTICATED))

            val dto = service(configuredRepo(), companion).status(testUserId)

            assertEquals(RecgovSessionState.EXPIRED, dto.session)
            assertEquals(RecGovSessionCodes.NOT_AUTHENTICATED, dto.detail)
        }

    @Test
    fun `status degrades instead of failing when the companion is unreachable`() =
        runBlocking {
            val companion = FakeCompanion(healthResult = CompanionSessionHealth.Unavailable("connection refused"))

            val dto = service(configuredRepo(), companion).status(testUserId)

            assertTrue(dto.configured)
            assertEquals(RecgovSessionState.COMPANION_UNAVAILABLE, dto.session)
        }

    @Test
    fun `status degrades when no companion is configured at all`() =
        runBlocking {
            val dto = service(configuredRepo(), companion = null).status(testUserId)

            assertEquals(RecgovSessionState.COMPANION_UNAVAILABLE, dto.session)
        }

    @Test
    fun `status surfaces an open MFA challenge so a remounted panel can resume it`() =
        runBlocking {
            val companion = FakeCompanion(loginResult = CompanionLoginResult.MfaRequired("chal-1", null))
            val svc = service(configuredRepo(), companion)

            assertFalse(svc.status(testUserId).mfaPending)

            svc.login(testUserId)
            assertTrue(svc.status(testUserId).mfaPending)

            companion.mfaResult = CompanionLoginResult.Ok
            svc.completeMfa(testUserId, "123456")
            assertFalse(svc.status(testUserId).mfaPending)
        }

    // ── secrets never reach a string ─────────────────────────────────────────

    @Test
    fun `neither the request nor the decrypted credentials print their password`() {
        val req = UpdateRecgovRequest("ada@example.com", "hunter2-secret")
        val credentials = RecGovCredentialService.Credentials("ada@example.com", "hunter2-secret")

        for (rendered in listOf(req.toString(), credentials.toString())) {
            assertFalse(rendered.contains("hunter2-secret"), rendered)
            assertTrue(rendered.contains("ada@example.com"), rendered)
        }
    }

    @Test
    fun `credentials stored without an encryption key read back as unconfigured`() =
        runBlocking {
            // The cipher went away (key rotated out) but ciphertext is still on the row.
            val dto = service(configuredRepo(), withCipher = null).status(testUserId)

            assertFalse(dto.configured)
            assertEquals(RecgovSessionState.NOT_CONFIGURED, dto.session)
        }
}
