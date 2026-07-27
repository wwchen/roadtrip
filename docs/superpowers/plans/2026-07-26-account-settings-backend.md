# Account Settings — Backend (Track B) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist per-user settings (notification email, Slack bot token, Slack channel), expose read/write endpoints gated to the signed-in user, and validate/test the Slack token against Slack — without ever returning the token to the browser.

**Architecture:** New `user_settings` table (one row per user). SQL lives in a new `UserSettingsRepo`; the token is sealed with a new AES-GCM `SecretCipher` before it reaches the repo. A new `UserSettingsService` orchestrates validation, Slack `auth.test`, and per-user test sends through an extended Slack client boundary. New `/api/settings/*` routes are the HTTP shell, each declaring `.access(RouteAccess.User)` and reading the caller's `UserId` from the ambient `Principal.User`.

**Tech Stack:** Kotlin, Ktor, jOOQ (codegen from Flyway migrations at build time via Testcontainers), Koin DI, JUnit5 + kotlin.test, `SharedDbTest` (Testcontainers Postgres).

## Global Constraints

- Layering (from `docs/backend-architecture.md`): `routes → service → repo, clients`; `service → models`; SQL/jOOQ only in `repo/`; no business logic in routes; typed `@Serializable` DTOs, never hand-built JSON.
- The Slack bot token is **write-only across the API boundary**: no endpoint or DTO ever returns it. Reads return only `slack_configured: Boolean` and `slack_token_hint` (last 4 chars).
- Every route must call `.access(...)`; the boot guard (`registerKoinRoutes`) and `RouteAccessCoverageTest` fail the build otherwise. Settings routes are `RouteAccess.User`.
- A route never trusts a user id from the request body; identity comes from `call.principal()` as `Principal.User`.
- Nullable-config idiom: a missing encryption key is a first-class "token storage disabled" state (mirrors `AuthConfig.fromConfig` / `SlackConfig.fromConfig` returning null), not a boot failure.
- No inline magic constants: extract literals to named `const val`.
- Build/test: `./gradlew :backend:test`. jOOQ regenerates on any build; `JooqCodegenDriftTest` asserts generated code matches migrations.

---

### Task 1: `user_settings` migration + jOOQ regen

**Files:**
- Create: `backend/src/main/resources/db/migration/V48__user_settings.sql`
- Test (existing, must pass): `backend/src/test/kotlin/ca/floo/roadtrip/repo/JooqCodegenDriftTest.kt`

**Interfaces:**
- Produces: table `user_settings` and generated jOOQ type `ca.floo.roadtrip.db.generated.tables.UserSettings.Companion.USER_SETTINGS` for later tasks.

- [ ] **Step 1: Write the migration**

```sql
-- Per-user settings. Named generically (not user_notification_settings) so
-- future non-notification preferences share the table without a new migration.
-- One row per user, created lazily on first write. The Slack token is stored
-- as AES-GCM ciphertext (see SecretCipher); only the last-4 hint is ever
-- returned to a client.
CREATE TABLE user_settings (
  user_id            BIGINT      PRIMARY KEY REFERENCES app_user(id) ON DELETE CASCADE,
  notification_email TEXT,                 -- NULL = fall back to app_user.email
  slack_channel      TEXT,                 -- NULL = channel unset
  slack_token_cipher BYTEA,                -- AES-GCM ciphertext; NULL = no token
  slack_token_hint   TEXT,                 -- last 4 chars; safe to return
  created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

- [ ] **Step 2: Regenerate jOOQ and run the drift test**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.JooqCodegenDriftTest'`
Expected: PASS (build regenerates `USER_SETTINGS`; drift test confirms generated code matches the new migration).

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/resources/db/migration/V48__user_settings.sql
git commit -m "feat(settings): user_settings table (V48)"
```

---

### Task 2: `SecretCipher` (AES-256-GCM) + config

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/security/SecretCipher.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/config/SecretsConfig.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/security/SecretCipherTest.kt`

**Interfaces:**
- Produces:
  - `class SecretCipher(key: ByteArray)` with `fun seal(plaintext: String): ByteArray` and `fun open(ciphertext: ByteArray): String`.
  - `data class SecretsConfig(val encryptionKey: ByteArray)` with `companion object { fun fromConfig(config: ConfigSection): SecretsConfig? }` — null when `encryption-key` is absent/blank (token storage disabled).

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.service.security

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SecretCipherTest {
    private val key = ByteArray(32) { it.toByte() }
    private val cipher = SecretCipher(key)

    @Test
    fun `seal then open round-trips`() {
        val sealed = cipher.seal("xoxb-super-secret")
        assertEquals("xoxb-super-secret", cipher.open(sealed))
    }

    @Test
    fun `each seal uses a fresh nonce`() {
        assertFalse(cipher.seal("same").contentEquals(cipher.seal("same")))
    }

    @Test
    fun `open rejects tampered ciphertext`() {
        val sealed = cipher.seal("x").also { it[it.size - 1] = (it.last() + 1).toByte() }
        assertFailsWith<Exception> { cipher.open(sealed) }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.security.SecretCipherTest'`
Expected: FAIL — `SecretCipher` unresolved.

- [ ] **Step 3: Implement `SecretCipher`**

```kotlin
package ca.floo.roadtrip.service.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val ALGORITHM = "AES"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val NONCE_BYTES = 12
private const val TAG_BITS = 128

/**
 * AES-256-GCM sealing for secrets at rest. Output layout is nonce||ciphertext+tag,
 * so [open] is self-describing. The key comes from config (see [ca.floo.roadtrip.config.SecretsConfig]);
 * a leaked database row is useless without it.
 */
class SecretCipher(key: ByteArray) {
    init { require(key.size == 32) { "encryption key must be 32 bytes (AES-256)" } }
    private val keySpec = SecretKeySpec(key, ALGORITHM)
    private val random = SecureRandom()

    fun seal(plaintext: String): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        }
        return nonce + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    fun open(ciphertext: ByteArray): String {
        val nonce = ciphertext.copyOfRange(0, NONCE_BYTES)
        val body = ciphertext.copyOfRange(NONCE_BYTES, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_BITS, nonce))
        }
        return String(cipher.doFinal(body), Charsets.UTF_8)
    }
}
```

- [ ] **Step 4: Implement `SecretsConfig`**

```kotlin
package ca.floo.roadtrip.config

import java.util.Base64

private const val ENCRYPTION_KEY = "encryption-key"

/**
 * Symmetric key for [ca.floo.roadtrip.service.security.SecretCipher], base64 of
 * 32 bytes. [fromConfig] returns null when absent/blank — a first-class
 * "secret storage disabled" state (settings that need it answer 503), mirroring
 * [AuthConfig.fromConfig] and [SlackConfig.fromConfig].
 */
data class SecretsConfig(val encryptionKey: ByteArray) {
    companion object {
        fun fromConfig(config: ConfigSection): SecretsConfig? {
            val raw = config.value(ENCRYPTION_KEY) ?: return null
            val decoded = Base64.getDecoder().decode(raw)
            require(decoded.size == 32) { "$ENCRYPTION_KEY must be base64 of 32 bytes" }
            return SecretsConfig(decoded)
        }
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.security.SecretCipherTest'`
Expected: PASS.

- [ ] **Step 6: Wire `SecretsConfig` into `AppConfig`**

Add a `secrets: SecretsConfig?` field to `AppConfig`, populated from `config.section("security")` in `AppConfig`'s loader alongside the existing `auth`/`slack` sections. (Read `config/AppConfig.kt` and follow the existing per-section pattern.)

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/security/SecretCipher.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/config/SecretsConfig.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/security/SecretCipherTest.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/config/AppConfig.kt
git commit -m "feat(settings): AES-GCM SecretCipher + secrets config"
```

---

### Task 3: `UserSettingsRepo`

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/repo/UserSettingsRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/UserSettingsRepoTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt`

**Interfaces:**
- Consumes: `USER_SETTINGS` (Task 1), `UserId`.
- Produces:
  - `data class UserSettingsRepo.Settings(val notificationEmail: String?, val slackChannel: String?, val slackTokenCipher: ByteArray?, val slackTokenHint: String?)`
  - `class UserSettingsRepo(ctx: DSLContext)` with:
    - `fun find(userId: UserId): Settings?`
    - `fun upsertNotifications(userId: UserId, notificationEmail: String?, slackChannel: String?)` — leaves token columns untouched.
    - `fun setSlackToken(userId: UserId, cipher: ByteArray, hint: String)`
    - `fun clearSlack(userId: UserId)` — nulls token cipher + hint (leaves channel).

- [ ] **Step 1: Write the failing test**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.model.domain.auth.UserId
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserSettingsRepoTest : SharedDbTest() {
    private val userRepo by lazy { UserRepo(ctx) }
    private val repo by lazy { UserSettingsRepo(ctx) }

    @BeforeEach fun cleanup() { ctx.execute("DELETE FROM app_user") }

    private fun newUser(): UserId =
        userRepo.create(email = "s@example.com", displayName = null, isEmailVerified = true).id

    @Test fun `find is null before any write`() = assertNull(repo.find(newUser()))

    @Test fun `upsertNotifications creates then updates without touching token`() {
        val u = newUser()
        repo.setSlackToken(u, byteArrayOf(1, 2, 3), "3f9a")
        repo.upsertNotifications(u, notificationEmail = "a@x.com", slackChannel = "#c")
        val s = repo.find(u)!!
        assertEquals("a@x.com", s.notificationEmail)
        assertEquals("#c", s.slackChannel)
        assertContentEquals(byteArrayOf(1, 2, 3), s.slackTokenCipher)
        assertEquals("3f9a", s.slackTokenHint)
    }

    @Test fun `clearSlack nulls token but keeps channel`() {
        val u = newUser()
        repo.upsertNotifications(u, null, "#c")
        repo.setSlackToken(u, byteArrayOf(9), "beef")
        repo.clearSlack(u)
        val s = repo.find(u)!!
        assertNull(s.slackTokenCipher); assertNull(s.slackTokenHint)
        assertEquals("#c", s.slackChannel)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.UserSettingsRepoTest'`
Expected: FAIL — `UserSettingsRepo` unresolved.

- [ ] **Step 3: Implement the repo**

```kotlin
package ca.floo.roadtrip.repo

import ca.floo.roadtrip.db.generated.tables.UserSettings.Companion.USER_SETTINGS
import ca.floo.roadtrip.model.domain.auth.UserId
import org.jooq.DSLContext
import java.time.OffsetDateTime

/** Persistence for `user_settings`. Stores the Slack token only as ciphertext. */
class UserSettingsRepo(private val ctx: DSLContext) {
    data class Settings(
        val notificationEmail: String?,
        val slackChannel: String?,
        val slackTokenCipher: ByteArray?,
        val slackTokenHint: String?,
    )

    fun find(userId: UserId): Settings? =
        ctx.select(
            USER_SETTINGS.NOTIFICATION_EMAIL, USER_SETTINGS.SLACK_CHANNEL,
            USER_SETTINGS.SLACK_TOKEN_CIPHER, USER_SETTINGS.SLACK_TOKEN_HINT,
        ).from(USER_SETTINGS).where(USER_SETTINGS.USER_ID.eq(userId.value)).fetchOne()
            ?.let {
                Settings(
                    it[USER_SETTINGS.NOTIFICATION_EMAIL], it[USER_SETTINGS.SLACK_CHANNEL],
                    it[USER_SETTINGS.SLACK_TOKEN_CIPHER], it[USER_SETTINGS.SLACK_TOKEN_HINT],
                )
            }

    fun upsertNotifications(userId: UserId, notificationEmail: String?, slackChannel: String?) {
        ctx.insertInto(USER_SETTINGS)
            .set(USER_SETTINGS.USER_ID, userId.value)
            .set(USER_SETTINGS.NOTIFICATION_EMAIL, notificationEmail)
            .set(USER_SETTINGS.SLACK_CHANNEL, slackChannel)
            .onConflict(USER_SETTINGS.USER_ID).doUpdate()
            .set(USER_SETTINGS.NOTIFICATION_EMAIL, notificationEmail)
            .set(USER_SETTINGS.SLACK_CHANNEL, slackChannel)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .execute()
    }

    fun setSlackToken(userId: UserId, cipher: ByteArray, hint: String) {
        ctx.insertInto(USER_SETTINGS)
            .set(USER_SETTINGS.USER_ID, userId.value)
            .set(USER_SETTINGS.SLACK_TOKEN_CIPHER, cipher)
            .set(USER_SETTINGS.SLACK_TOKEN_HINT, hint)
            .onConflict(USER_SETTINGS.USER_ID).doUpdate()
            .set(USER_SETTINGS.SLACK_TOKEN_CIPHER, cipher)
            .set(USER_SETTINGS.SLACK_TOKEN_HINT, hint)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .execute()
    }

    fun clearSlack(userId: UserId) {
        ctx.update(USER_SETTINGS)
            .setNull(USER_SETTINGS.SLACK_TOKEN_CIPHER)
            .setNull(USER_SETTINGS.SLACK_TOKEN_HINT)
            .set(USER_SETTINGS.UPDATED_AT, OffsetDateTime.now())
            .where(USER_SETTINGS.USER_ID.eq(userId.value))
            .execute()
    }
}
```

- [ ] **Step 4: Register in Koin** — add `single { UserSettingsRepo(get()) }` to `repoModule` in `di/RepoModule.kt` (with the import).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.UserSettingsRepoTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/UserSettingsRepo.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/repo/UserSettingsRepoTest.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RepoModule.kt
git commit -m "feat(settings): UserSettingsRepo"
```

---

### Task 4: Slack client boundary — per-request token

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/client/slack/*` (the Slack HTTP client) — read the directory first and follow its style.
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/notification/slack/PerUserSlackTest.kt`

**Interfaces:**
- Produces (on the Slack client, provider-neutral — no Slack wire types escape):
  - `data class SlackIdentity(val teamName: String?, val botName: String?)`
  - `suspend fun authTest(token: String): SlackIdentity?` — null when Slack rejects the token (`invalid_auth`).
  - `suspend fun postMessage(token: String, channel: String, text: String, attachments: ...): Boolean` — overload/param accepting a caller token instead of the global config token.

- [ ] **Step 1: Write the failing test** — inject a fake Slack transport, assert `authTest` returns null on `{ ok: false, error: "invalid_auth" }` and a populated `SlackIdentity` on `{ ok: true, team, ... }`, and that `postMessage(token, ...)` sends with the caller token in the Authorization header.

```kotlin
// See existing SlackNotificationService tests for the fake-transport pattern.
// Assert: authTest("bad") == null; authTest("good")?.teamName == "Acme";
// postMessage("tok", "#c", "hi", ...) uses Bearer tok.
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.notification.slack.PerUserSlackTest'`
Expected: FAIL — `authTest` / token overload unresolved.

- [ ] **Step 3: Implement** — add `authTest(token)` (calls Slack `auth.test`) and a token-parameterized `postMessage` to the client. Keep the existing global-token methods delegating to the new ones with the config token. Map upstream JSON to `SlackIdentity` inside the client; do not surface Slack DTOs.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.notification.slack.PerUserSlackTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/client/slack/ \
        backend/src/test/kotlin/ca/floo/roadtrip/service/notification/slack/PerUserSlackTest.kt
git commit -m "feat(settings): per-request Slack token (authTest + postMessage overload)"
```

---

### Task 5: Settings DTOs

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/SettingsResponseDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/UpdateProfileRequest.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/UpdateNotificationsRequest.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/SlackTestResponseDto.kt`

**Interfaces:**
- Produces the serializable shapes below. Note `slackToken` is **request-only**; no response type contains it.

- [ ] **Step 1: Write the DTOs**

```kotlin
// SettingsResponseDto.kt
package ca.floo.roadtrip.model.api
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SettingsResponseDto(
    val profile: ProfileDto,
    val notifications: NotificationsDto,
)

@Serializable
data class ProfileDto(
    @SerialName("display_name") val displayName: String?,
    @SerialName("login_email") val loginEmail: String,
    @SerialName("is_email_verified") val isEmailVerified: Boolean,
    val roles: List<String>,
    @SerialName("provider_label") val providerLabel: String?,
)

@Serializable
data class NotificationsDto(
    @SerialName("notification_email") val notificationEmail: String?,
    @SerialName("slack_channel") val slackChannel: String?,
    @SerialName("slack_configured") val slackConfigured: Boolean,
    @SerialName("slack_token_hint") val slackTokenHint: String?,
)
```

```kotlin
// UpdateProfileRequest.kt
package ca.floo.roadtrip.model.api
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UpdateProfileRequest(@SerialName("display_name") val displayName: String?)
```

```kotlin
// UpdateNotificationsRequest.kt
package ca.floo.roadtrip.model.api
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// slackToken semantics: null = leave unchanged; non-blank = set/replace.
// Clearing is done via DELETE .../slack, not this request.
@Serializable
data class UpdateNotificationsRequest(
    @SerialName("notification_email") val notificationEmail: String? = null,
    @SerialName("slack_channel") val slackChannel: String? = null,
    @SerialName("slack_token") val slackToken: String? = null,
)
```

```kotlin
// SlackTestResponseDto.kt
package ca.floo.roadtrip.model.api
import kotlinx.serialization.Serializable

@Serializable
data class SlackTestResponseDto(val sent: Boolean, val channel: String? = null)
```

- [ ] **Step 2: Compile**

Run: `./gradlew :backend:compileKotlin`
Expected: PASS.

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/SettingsResponseDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/UpdateProfileRequest.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/UpdateNotificationsRequest.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/model/api/SlackTestResponseDto.kt
git commit -m "feat(settings): settings DTOs"
```

---

### Task 6: `UserSettingsService`

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/settings/UserSettingsService.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/settings/UserSettingsServiceTest.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt`

**Interfaces:**
- Consumes: `UserRepo`, `UserSettingsRepo`, `SecretCipher?`, the Slack client (Task 4), `providerLabel: String?`.
- Produces `class UserSettingsService(...)` with:
  - `fun read(principal: Principal.User): SettingsResponseDto`
  - `fun updateProfile(userId: UserId, req: UpdateProfileRequest): SettingsResponseDto` (throws `SettingsError.InvalidField` on bad input)
  - `suspend fun updateNotifications(principal: Principal.User, req: UpdateNotificationsRequest): SettingsResponseDto` (throws `SettingsError.InvalidField`, `SettingsError.SlackRejected`, `SettingsError.EncryptionUnavailable`)
  - `fun disconnectSlack(userId: UserId): SettingsResponseDto`
  - `suspend fun sendSlackTest(userId: UserId, channelOverride: String?): SlackTestResponseDto` (throws `SettingsError.SlackNotConfigured`, `SettingsError.SlackSendFailed`)
  - `sealed class SettingsError : RuntimeException` with the cases named above.

**Key policy (encode as tests):**
- `read` never puts the token in the DTO — only `slackConfigured` (`cipher != null`) and `slackTokenHint`.
- `updateNotifications`: validate email format + channel length (`MAX_SLACK_CHANNEL_CHARS = 255`); if `slackToken` non-blank → `authTest`; null identity → `SlackRejected`; else `seal` + `setSlackToken` with `hint = token.takeLast(4)`; if cipher is null (no key configured) → `EncryptionUnavailable`.
- `notificationEmail` in the DTO falls back to `app_user.email` when the stored value is null.

- [ ] **Step 1: Write the failing tests** (fake repos + fake Slack client + a real `SecretCipher` with a test key)

```kotlin
package ca.floo.roadtrip.service.settings

// Tests to assert:
// 1. read(): token never appears; slackConfigured reflects cipher presence; hint passed through.
// 2. updateProfile(): display name persisted via UserRepo.updateProfile.
// 3. updateNotifications() with a valid token: authTest called, seal stored, hint == last 4.
// 4. updateNotifications() with a bad token: throws SlackRejected, nothing persisted.
// 5. updateNotifications() with no encryption key (cipher null) + token present: throws EncryptionUnavailable.
// 6. updateNotifications() with invalid email: throws InvalidField.
// 7. disconnectSlack(): repo.clearSlack called; DTO shows slackConfigured=false.
// 8. sendSlackTest(): resolves stored token+channel; SlackNotConfigured when no token.
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.settings.UserSettingsServiceTest'`
Expected: FAIL — service unresolved.

- [ ] **Step 3: Implement `UserSettingsService`** per the interface + policy above. Extract `MAX_SLACK_CHANNEL_CHARS = 255` and an email regex to named `const val`. Open the stored cipher only to send tests; never return plaintext.

- [ ] **Step 4: Register in Koin** — add `single { UserSettingsService(get(), get(), getOrNull(), get(), providerLabelFrom(get())) }` to `serviceModule` (resolve `SecretCipher?` via `getOrNull()`; provide `SecretCipher` as `single { get<AppConfig>().secrets?.let { SecretCipher(it.encryptionKey) } }` or a nullable provider — follow ServiceModule's existing nullable-wiring pattern).

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.settings.UserSettingsServiceTest'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/settings/ \
        backend/src/test/kotlin/ca/floo/roadtrip/service/settings/ \
        backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt
git commit -m "feat(settings): UserSettingsService"
```

---

### Task 7: `/api/settings/*` routes

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/settings/SettingsRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt` (register + inject service)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/api/settings/SettingsRoutesTest.kt`

**Interfaces:**
- Consumes: `UserSettingsService`, `ApplicationCall.principal()`, `RouteAccess.User`, `describeApi`, `access`, `respondApiError`.
- Endpoints (all `.access(RouteAccess.User)`):
  - `GET /api/settings` → `SettingsResponseDto`
  - `PUT /api/settings/profile` (body `UpdateProfileRequest`) → `SettingsResponseDto`
  - `PUT /api/settings/notifications` (body `UpdateNotificationsRequest`) → `SettingsResponseDto`
  - `DELETE /api/settings/notifications/slack` → `SettingsResponseDto`
  - `POST /api/settings/notifications/slack/test` (body `{ "channel"?: String }`) → `SlackTestResponseDto`

- [ ] **Step 1: Write the failing tests** — use the app's route test harness (see `route/auth/*Test` or `AvailabilityWatchRoutesTest` for `withTestApplication` + principal injection). Assert:
  - anonymous → 401 on every endpoint;
  - a `Principal.User` → 200 and the token never appears in `GET` output;
  - `PUT /notifications` with a Slack-rejected token → 400 `slack_invalid_auth`;
  - `POST /notifications/slack/test` with no stored token → 503 `slack_not_configured`;
  - `DELETE /notifications/slack` → 200 with `slack_configured: false`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.settings.SettingsRoutesTest'`
Expected: FAIL — routes unresolved.

- [ ] **Step 3: Implement the routes** — HTTP shell only: read `call.principal() as? Principal.User` (else the interceptor already 401s), decode the typed body, call the service, map `SettingsError` cases to status codes (`InvalidField`→400 `invalid_field`; `SlackRejected`→400 `slack_invalid_auth`; `EncryptionUnavailable`/`SlackNotConfigured`→503; `SlackSendFailed`→502), and respond the DTO. Each route ends `.describeApi("settings", "...").access(RouteAccess.User)`.

- [ ] **Step 4: Register in `RouteModule`** — inject `UserSettingsService` and add `settingsRoutes(userSettingsService)` inside the `routing { }` block. Confirm the boot access-coverage guard still passes.

- [ ] **Step 5: Run the full backend test suite**

Run: `./gradlew :backend:test`
Expected: PASS (including `RouteAccessCoverageTest`).

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/route/api/settings/ \
        backend/src/test/kotlin/ca/floo/roadtrip/route/api/settings/ \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt
git commit -m "feat(settings): /api/settings routes (User-gated)"
```

---

### Task 8 (deferred follow-up): route alerts to the owner's Slack destination

**Not built in this plan** — depends on watches carrying an owner (`Principal.User`), which lands with RFC 0010 PR 2's `User`-gating of watches. When that exists:

- In the Slack alert path (`WatchAlertDispatcher` / `SlackNotificationService`), resolve the owning user's `UserSettingsRepo.find(...)`, open the token via `SecretCipher`, and post with the per-user token + channel; fall back to the global `SlackConfig` when the user has none.
- Add tests: per-user destination used when set; global fallback when unset; global-disabled + user-disabled = no-op (unchanged behavior).

Capture as its own small plan once watch-ownership is available.

---

## Backend self-review notes

- Spec coverage: `user_settings` table (T1), encryption at rest (T2), repo (T3), per-user Slack client (T4), DTOs (T5), service policy incl. validate-on-save + write-only token (T6), User-gated routes + user-scoped test endpoint (T7), alert wiring called out as deferred (T8). ✅
- Write-only token invariant is asserted in T6 and T7 tests (token never in any response). ✅
- Type consistency: `UserSettingsRepo.Settings`, `SettingsError` cases, and DTO field names are referenced identically across T3/T5/T6/T7. ✅
