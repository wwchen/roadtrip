# Role Bootstrapping via Email Allowlist Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Grant DB-backed roles (`ADMIN` today) to users on sign-in when their verified email appears in a role→emails allowlist committed to config.

**Architecture:** `AuthConfig` parses an inline `auth.role-emails` YAML map into `Map<Role, Set<String>>`. `UserProvisioningService.provision` — the existing home for account-linking policy — is restructured so all resolution paths converge on a resolved `userId`, then grants each role whose email set contains the identity's verified email. Grant-only (never revokes), applied on every sign-in, inside the existing provisioning transaction.

**Tech Stack:** Kotlin, jOOQ, Ktor config (`ConfigSection`), JUnit 5 + kotlin.test, `SharedDbTest` (real Postgres via testcontainers-style shared fixture).

## Global Constraints

- **Layering:** SQL/jOOQ stays in `repo` classes only. The grant uses the existing `UserRepo.grantRole(id, role)`; no new repo methods, no SQL in the service. (AGENTS.md)
- **No inline magic constants:** the config key path segment `role-emails` is a named `const val`. (AGENTS.md)
- **Grant-only:** config never revokes a role; manual `grantRole`/`revokeRole` and SQL grants are never clobbered. (spec)
- **Verified-email gate:** an unverified email claim grants nothing, mirroring the account-linking rule already in `UserProvisioningService`. (spec)
- **No secret, no env plumbing:** the list is an inline committed literal in `application-prod.yaml`. No `docker-compose.yml`, `secrets/registry.yaml`, or `SecretRegistryDriftTest` changes. (spec)
- **Kotlin toolchain 21:** Gradle provisions its own JDK; do NOT export `JAVA_HOME`. Run backend tests with `./gradlew` from repo root. (memory: backend-build-jdk17)

---

## File Structure

- `backend/src/main/kotlin/ca/floo/roadtrip/config/AuthConfig.kt` — add `roleGrants: Map<Role, Set<String>>` field + parsing in `fromConfig`. (modify)
- `backend/src/test/kotlin/ca/floo/roadtrip/config/AuthConfigTest.kt` — parsing tests. (modify)
- `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningService.kt` — new `roleGrants` ctor param; converge paths; grant matching roles. (modify)
- `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningServiceTest.kt` — grant behavior tests. (modify)
- `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt:290` — pass `authConfig.roleGrants` into `UserProvisioningService`. (modify)
- `backend/src/main/resources/application-prod.yaml` — inline `auth.role-emails.admin` list. (modify)

Task order: **1** (config parsing) → **2** (service grant, depends on Task 1's `roleGrants` field) → **3** (wiring + prod config, depends on Tasks 1 & 2).

---

### Task 1: Parse `auth.role-emails` into `AuthConfig.roleGrants`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/AuthConfig.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/config/AuthConfigTest.kt`

**Interfaces:**
- Consumes: `ConfigSection.section(name)`, `ConfigSection.absoluteKeys()`, `ConfigSection.relativeKey(absoluteKey)`, `ConfigSection.csvSet(name)`; `Role.parse(value): Role?` from `ca.floo.roadtrip.model.domain.auth.Role`.
- Produces: `AuthConfig.roleGrants: Map<Role, Set<String>>` (emails lowercased; empty map when absent). Consumed by Task 2 and Task 3.

**Background:** The YAML flattener (`ApplicationProperties.flattenMap`) collapses a YAML list into a comma-joined string, so an inline array `admin: [a, b]` arrives as the flat key `role-emails.admin` → `"a,b"`. `csvSet` splits it back. `relativeKey` on the `role-emails` subsection turns the absolute key `roadtrip.auth.role-emails.admin` into `admin`.

- [ ] **Step 1: Write the failing tests**

Add to `AuthConfigTest.kt` (the `section(...)` helper and `clerk` map already exist in this file):

```kotlin
    @Test
    fun `role-emails parses an inline array into a lowercased set keyed by role`() {
        val config =
            assertNotNull(
                AuthConfig.fromConfig(
                    // The flattener turns the YAML list into a comma-joined string,
                    // which is exactly what a flat map key holds at this layer.
                    section(clerk + ("role-emails.admin" to "You@Example.com, other@example.com")),
                ),
            )

        assertEquals(mapOf(Role.ADMIN to setOf("you@example.com", "other@example.com")), config.roleGrants)
    }

    @Test
    fun `role-emails skips unknown role keys without crashing`() {
        val config =
            assertNotNull(
                AuthConfig.fromConfig(
                    section(clerk + mapOf("role-emails.admin" to "a@example.com", "role-emails.wizard" to "b@example.com")),
                ),
            )

        assertEquals(mapOf(Role.ADMIN to setOf("a@example.com")), config.roleGrants)
    }

    @Test
    fun `an empty list for a role key yields an empty set for that role`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk + ("role-emails.admin" to ""))))

        assertEquals(mapOf(Role.ADMIN to emptySet()), config.roleGrants)
    }

    @Test
    fun `absent role-emails yields an empty map`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk)))

        assertEquals(emptyMap(), config.roleGrants)
    }
```

Add the import at the top of the file:

```kotlin
import ca.floo.roadtrip.model.domain.auth.Role
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.config.AuthConfigTest"`
Expected: FAIL — `AuthConfig` has no `roleGrants` property (compile error).

- [ ] **Step 3: Add the field and parsing to `AuthConfig.kt`**

Add a `const val` near the other `*_KEY` constants at the top of the file:

```kotlin
private const val ROLE_EMAILS_KEY = "role-emails"
```

Add the import:

```kotlin
import ca.floo.roadtrip.model.domain.auth.Role
```

Add the field to the `data class AuthConfig(...)` constructor (after `embeddedDomain`):

```kotlin
    /**
     * Verified emails that are auto-granted a role on sign-in, keyed by role.
     * Grant-only and inert when empty; see UserProvisioningService for how it is
     * applied. Committed config, not a secret — knowing the list grants nothing
     * without control of the address's verified IdP account.
     */
    val roleGrants: Map<Role, Set<String>>,
```

In `fromConfig`, before the `return AuthConfig(`, build the map:

```kotlin
            val roleGrants = parseRoleGrants(config.section(ROLE_EMAILS_KEY))
```

Add `roleGrants = roleGrants,` to the `AuthConfig(...)` constructor call.

Add this private helper inside the `companion object`, after `fromConfig`:

```kotlin
        /**
         * Enumerates the immediate child keys of `role-emails` (each a [Role]
         * wireValue), parsing each into a lowercased email set. Unknown role
         * keys are skipped so a stale config key never fails boot.
         */
        private fun parseRoleGrants(section: ConfigSection): Map<Role, Set<String>> =
            section
                .absoluteKeys()
                .mapNotNull { section.relativeKey(it) }
                .mapNotNull { childKey -> Role.parse(childKey)?.let { it to section.csvSet(childKey).map(String::lowercase).toSet() } }
                .toMap()
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.config.AuthConfigTest"`
Expected: PASS (all tests, including the pre-existing ones).

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/config/AuthConfig.kt backend/src/test/kotlin/ca/floo/roadtrip/config/AuthConfigTest.kt
git commit -m "feat(auth): parse role-emails allowlist into AuthConfig.roleGrants" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Grant matching roles on sign-in in `UserProvisioningService`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningService.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningServiceTest.kt`

**Interfaces:**
- Consumes: `roleGrants: Map<Role, Set<String>>` (from Task 1); existing `UserRepo.grantRole(id: UserId, role: Role): Boolean`, `UserRepo.rolesFor(id: UserId): Set<Role>`.
- Produces: `UserProvisioningService(ctx, roleGrants)` — new constructor signature. Consumed by Task 3 (`RouteModule`) and existing callers in tests.

**Background:** `provision` runs inside `ctx.transactionResult`. Today the returning-identity branch does an early `return@transactionResult identity.userId`. The grant must apply on *every* sign-in, so that early return has to feed the same tail as the link/create paths. The verified email and its status come straight from `claims` (`claims.email`, `claims.isEmailVerified`).

- [ ] **Step 1: Write the failing tests**

The existing `UserProvisioningServiceTest` constructs `UserProvisioningService(ctx)` in a `by lazy` and has a `claims(...)` helper (default `email = "user@example.com"`, `isEmailVerified = true`). Change the provisioning field to accept a per-test grant map, then add tests.

Replace the existing field declaration:

```kotlin
    private val provisioning by lazy { UserProvisioningService(ctx) }
```

with a factory plus a default:

```kotlin
    private fun provisioningWith(roleGrants: Map<Role, Set<String>>) = UserProvisioningService(ctx, roleGrants)
    private val provisioning by lazy { provisioningWith(emptyMap()) }
```

Add imports:

```kotlin
import ca.floo.roadtrip.model.domain.auth.Role
```

Add these tests:

```kotlin
    @Test
    fun `a verified email in the admin allowlist is granted ADMIN`() {
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("user@example.com")))

        val userId = svc.provision(AUTH0, claims("auth0|admin"))

        assertTrue(Role.ADMIN in userRepo.rolesFor(userId))
    }

    @Test
    fun `a verified email not in any allowlist gets no role`() {
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("someone-else@example.com")))

        val userId = svc.provision(AUTH0, claims("auth0|plain"))

        assertTrue(userRepo.rolesFor(userId).isEmpty())
    }

    @Test
    fun `an UNVERIFIED email in the admin allowlist is NOT granted ADMIN`() {
        // Mirrors the account-linking rule: an unverified address confers no authority.
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("newcomer@example.com")))

        val userId =
            svc.provision(
                AUTH0,
                claims(
                    "auth0|unverified",
                    email = "newcomer@example.com",
                    isEmailVerified = false,
                    upstreamProvider = null,
                    upstreamSubject = null,
                ),
            )

        assertTrue(Role.ADMIN !in userRepo.rolesFor(userId))
    }

    @Test
    fun `a returning identity added to the allowlist later is granted on next sign-in`() {
        // First sign-in with no allowlist: account exists, no role.
        val userId = provisioning.provision(AUTH0, claims("auth0|returning"))
        assertTrue(userRepo.rolesFor(userId).isEmpty())

        // Email is added to the allowlist; the SAME identity signs in again.
        // This exercises the returning-identity short-circuit path.
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("user@example.com")))
        val again = svc.provision(AUTH0, claims("auth0|returning"))

        assertEquals(userId, again)
        assertTrue(Role.ADMIN in userRepo.rolesFor(userId))
    }

    @Test
    fun `granting is idempotent across repeat sign-ins`() {
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("user@example.com")))

        val userId = svc.provision(AUTH0, claims("auth0|idem"))
        svc.provision(AUTH0, claims("auth0|idem"))

        assertEquals(setOf(Role.ADMIN), userRepo.rolesFor(userId))
    }

    @Test
    fun `matching is case-insensitive on the email`() {
        val svc = provisioningWith(mapOf(Role.ADMIN to setOf("user@example.com")))

        val userId = svc.provision(AUTH0, claims("auth0|case", email = "User@Example.com"))

        assertTrue(Role.ADMIN in userRepo.rolesFor(userId))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.auth.UserProvisioningServiceTest"`
Expected: FAIL — `UserProvisioningService` constructor takes only `ctx` (compile error on `provisioningWith`).

- [ ] **Step 3: Add the constructor param and grant logic**

In `UserProvisioningService.kt`, change the constructor:

```kotlin
class UserProvisioningService(
    private val ctx: DSLContext,
    private val roleGrants: Map<Role, Set<String>> = emptyMap(),
) {
```

Add the import:

```kotlin
import ca.floo.roadtrip.model.domain.auth.Role
```

Restructure `provision` so every path converges before the grant. Replace the current body from the returning-identity block through the `link`/return with this — the returning-identity branch now assigns `userId` instead of returning early, and a single tail runs the grant and returns:

```kotlin
    fun provision(
        provider: String,
        claims: IdentityClaims,
    ): UserId =
        ctx.transactionResult { config ->
            val txn = config.dsl()
            val userRepo = UserRepo(txn)
            val userIdentityRepo = UserIdentityRepo(txn)

            // The common path: this identity has signed in before.
            val returning = userIdentityRepo.findByProviderSubject(provider, claims.subject)
            val userId =
                if (returning != null) {
                    userIdentityRepo.refresh(returning.id, claims)
                    if (claims.isEmailVerified) userRepo.markEmailVerified(returning.userId)
                    returning.userId
                } else {
                    val email =
                        claims.email
                            ?: throw AuthException("identity ${claims.subject} from '$provider' carries no email address")

                    val resolved =
                        userByUpstreamIdentity(userIdentityRepo, provider, claims)
                            ?: userByVerifiedEmail(userRepo, claims, email)
                            ?: createUser(userRepo, claims, email)

                    userIdentityRepo.link(resolved, provider, claims)
                    resolved
                }

            grantConfiguredRoles(userRepo, claims, userId)
            userId
        }

    /**
     * Grants every role whose allowlist contains this identity's verified email.
     * Grant-only and idempotent (see [UserRepo.grantRole]); an unverified email
     * grants nothing, mirroring the linking rule enforced elsewhere here.
     */
    private fun grantConfiguredRoles(
        userRepo: UserRepo,
        claims: IdentityClaims,
        userId: UserId,
    ) {
        if (!claims.isEmailVerified) return
        val email = claims.email?.lowercase() ?: return
        roleGrants.forEach { (role, emails) ->
            if (email in emails && userRepo.grantRole(userId, role)) {
                log.info("granted {} to user_id={} via role-emails allowlist", role, userId.value)
            }
        }
    }
```

Leave `userByUpstreamIdentity`, `userByVerifiedEmail`, and `createUser` unchanged.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests "ca.floo.roadtrip.service.auth.UserProvisioningServiceTest"`
Expected: PASS — the new grant tests plus all pre-existing provisioning tests (returning-identity, vendor-swap, verified-email link, unverified-refusal, no-email) still green, confirming the restructure preserved behavior.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningService.kt backend/src/test/kotlin/ca/floo/roadtrip/service/auth/UserProvisioningServiceTest.kt
git commit -m "feat(auth): grant allowlisted roles on every verified sign-in" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Wire `roleGrants` through `RouteModule` and set the prod allowlist

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt:290`
- Modify: `backend/src/main/resources/application-prod.yaml`

**Interfaces:**
- Consumes: `authConfig.roleGrants` (Task 1); `UserProvisioningService(ctx, roleGrants)` (Task 2).
- Produces: nothing new — this is the composition + config that makes the feature live in prod.

**Background:** `RouteModule` constructs `UserProvisioningService(ctx)` at line 290 inside the auth-wiring builder where `authConfig` is already in scope. `application-prod.yaml` has no `auth:` block today; the deep-merge in `ApplicationProperties.mergeMaps` lets us add one that overlays the base. There is no verifiable automated test for the prod YAML value itself (it is environment config); Task 1's tests already prove the parsing, and Task 2's prove the grant. So this task's "test" is the full suite plus a diff sanity-read.

- [ ] **Step 1: Wire the constructor**

In `RouteModule.kt`, change line 290 from:

```kotlin
                userProvisioningService = UserProvisioningService(ctx),
```

to:

```kotlin
                userProvisioningService = UserProvisioningService(ctx, authConfig.roleGrants),
```

- [ ] **Step 2: Add the prod allowlist**

Edit `backend/src/main/resources/application-prod.yaml`, adding an `auth` block under `roadtrip:` (sibling of `db`, `booking`, etc.). Use the real bootstrap admin email:

```yaml
  auth:
    role-emails:
      admin:
        - will@example.com
```

Replace `will@example.com` with the actual Clerk-verified admin email. Leave the base `application.yaml` with no `role-emails` (empty map ⇒ nobody granted locally).

- [ ] **Step 3: Verify the full backend suite passes**

Run: `./gradlew :backend:test`
Expected: PASS. Confirms the new constructor signature compiles against every caller (`RouteModule`, `AuthControllerTest`, `AuthRoutesTest`) — the default `roleGrants = emptyMap()` keeps those call sites valid — and no regression across the auth/provisioning suites.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt backend/src/main/resources/application-prod.yaml
git commit -m "feat(auth): wire role-emails allowlist and set prod admin" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Verification (end-to-end, after all tasks)

The DB-backed grant has a real runtime surface, so verify behavior — not just tests:

- [ ] Run the full backend suite once more: `./gradlew :backend:test`.
- [ ] Confirm ktlint passes (CI runs it separately): `./gradlew :backend:ktlintCheck` (or the committed pre-commit hook via `make install-hooks`).
- [ ] Sanity-read the diff: `AuthConfig.roleGrants` is populated only from `role-emails`; `UserProvisioningService` grants only on `isEmailVerified`; `RouteModule` passes `authConfig.roleGrants`; `application-prod.yaml` carries the intended admin email.

Note for the driver: `SharedDbTest` needs a Postgres fixture; if `:backend:test` hangs locally, push with `SKIP_PREPUSH=1` and read the PR checks rather than loop-debugging the daemon (see memory: use-ci-over-local-gradle).

## Self-Review Notes

- **Spec coverage:** config parsing (Task 1) ✓; generic over `Role` incl. unknown-key skip (Task 1) ✓; grant-only + every-sign-in incl. returning-identity path (Task 2) ✓; verified-gate (Task 2) ✓; inline array via flattener/`csvSet` (Task 1) ✓; committed prod config, no secret/compose/drift changes (Task 3, and explicitly nothing touches those files) ✓; placement in `provision` (Task 2) ✓.
- **Type consistency:** `roleGrants: Map<Role, Set<String>>` identical across Tasks 1→2→3; `UserProvisioningService(ctx, roleGrants)` signature consistent; `grantRole`/`rolesFor` match `UserRepo`'s real signatures.
- **Out of scope (unchanged):** no new role-gated routes/UI, no new `Role` values, no config-driven revocation.
