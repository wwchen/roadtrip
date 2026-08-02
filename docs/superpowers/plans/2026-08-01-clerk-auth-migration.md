# Auth0 → Clerk Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Point the existing vendor-agnostic OIDC auth layer at Clerk (`https://clerk.roadtrip.floo.ca`) with vendor-specific credential config, so switching providers — including rollback to Auth0 — is a single `AUTH_PROVIDER` env flip.

**Architecture:** The auth layer (RFC 0009) is already a generic OIDC client; "auth0" exists only as a config slug and one claims-parsing class. This plan adds a `ClerkClaimsDialect`, a human-readable provider label surfaced through `/api/me`, and per-vendor credential blocks in config. No SDKs, no DB migration, no frontend framework changes.

**Tech Stack:** Kotlin/Ktor backend (Gradle), Nimbus JOSE (existing), vanilla ES-module frontend (`npm test`), SOPS secrets vault (`secrets/manage.py`).

**Spec:** `docs/superpowers/specs/2026-08-01-clerk-auth-migration-design.md`

## Global Constraints

- Env var names (verbatim, no `ROADTRIP_` prefix — operator decision): `AUTH_PROVIDER`, `AUTH_AUTH0_ISSUER`, `AUTH_AUTH0_CLIENT_ID`, `AUTH_AUTH0_CLIENT_SECRET`, `AUTH_CLERK_ISSUER`, `AUTH_CLERK_CLIENT_ID`, `AUTH_CLERK_CLIENT_SECRET`. Other env vars keep their existing names.
- Default provider in `application.yaml`: `clerk`. Legacy `ROADTRIP_AUTH_*` vars are retired with no back-compat shim.
- No new dependencies (Gradle or npm). No DB migration. Auth0 dialect and its tests stay in-tree.
- `docker-compose.secrets.yml` is GENERATED — never edit by hand; edit `secrets/registry.yaml` and run `./secrets/manage.py generate`.
- Layering per `AGENTS.md`: routes → service → repo; no magic constants; dialects never decide policy.
- Every backend commit must pass: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt`.
- Frontend tests: `node --test $(find web -name '*.test.mjs' | sort)` — `web/` is dependency-free node:test modules; there is no root package.json (`npm test` belongs to `companion/` only).

---

### Task 1: ClerkClaimsDialect

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/ClerkClaimsDialect.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/ClaimsDialectRegistry.kt:40-47` (add to `default()`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/ClaimsDialectTest.kt`

**Interfaces:**
- Consumes: `ClaimsDialect` interface (`id: String`, `toIdentityClaims(token: VerifiedIdToken): IdentityClaims`), the file-private `token(...)` helper already defined at the top of `ClaimsDialectTest.kt`.
- Produces: `ClerkClaimsDialect` with `companion object { const val ID = "clerk" }` — Task 2 adds `displayName` to it; Task 4's yaml `provider: clerk` resolves to it via the registry.

- [ ] **Step 1: Write the failing tests**

In `ClaimsDialectTest.kt`, add after `StandardClaimsDialectTest`:

```kotlin
class ClerkClaimsDialectTest {
    private val dialect = ClerkClaimsDialect()

    @Test
    fun `clerk subjects are opaque and carry no upstream identity`() {
        // Clerk's sub is `user_…` with no embedded connection; migrated
        // accounts link on verified email instead (spec: email relink).
        val claims = dialect.toIdentityClaims(token("user_2abcDEF123"))

        assertEquals("user_2abcDEF123", claims.subject)
        assertEquals("user@example.com", claims.email)
        assertEquals("User", claims.displayName)
        assertNull(claims.upstreamProvider)
        assertNull(claims.upstreamSubject)
    }

    @Test
    fun `vendor-specific claims are ignored rather than misread as upstream identity`() {
        val claims = dialect.toIdentityClaims(token("user_2abcDEF123", mapOf("idp_id" to "ignored")))

        assertNull(claims.upstreamProvider)
        assertNull(claims.upstreamSubject)
    }
}
```

In `ClaimsDialectRegistryTest.each known slug selects its dialect`, add:

```kotlin
        assertEquals(ClerkClaimsDialect.ID, registry.forProvider("clerk").id)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.auth.ClerkClaimsDialectTest' --tests 'ca.floo.roadtrip.service.auth.ClaimsDialectRegistryTest'`
Expected: compile FAILURE — `unresolved reference: ClerkClaimsDialect`

- [ ] **Step 3: Write the implementation**

Create `ClerkClaimsDialect.kt`:

```kotlin
package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.IdentityClaims
import ca.floo.roadtrip.model.domain.auth.VerifiedIdToken

/**
 * Clerk.
 *
 * Clerk keeps `sub` opaque (`user_…`) and its OAuth-application id tokens
 * carry no upstream-connection claims, so this dialect reads only the
 * standard claims and reports no upstream identity. Accounts arriving from a
 * previous vendor link on verified email instead ([UserProvisioningService]).
 * If external-account enrichment via Clerk's Backend API is ever wanted, it
 * belongs here, inside the adapter — not in callers.
 */
internal class ClerkClaimsDialect : ClaimsDialect {
    override val id: String = ID

    override fun toIdentityClaims(token: VerifiedIdToken): IdentityClaims =
        IdentityClaims(
            subject = token.subject,
            email = token.email,
            isEmailVerified = token.isEmailVerified,
            displayName = token.name,
            upstreamProvider = null,
            upstreamSubject = null,
        )

    companion object {
        const val ID = "clerk"
    }
}
```

In `ClaimsDialectRegistry.default()`, add `ClerkClaimsDialect()` to the list:

```kotlin
                listOf(
                    Auth0ClaimsDialect(),
                    ClerkClaimsDialect(),
                    WorkOsClaimsDialect(),
                    StandardClaimsDialect(),
                ),
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.auth.*'`
Expected: PASS (all dialect + registry tests)

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/auth/ClerkClaimsDialect.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/auth/ClaimsDialectRegistry.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/auth/ClaimsDialectTest.kt
git commit -m "feat(auth): add Clerk claims dialect"
```

---

### Task 2: Provider display names on dialects

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/ClaimsDialect.kt` (interface)
- Modify: `Auth0ClaimsDialect.kt`, `WorkOsClaimsDialect.kt`, `StandardClaimsDialect.kt`, `ClerkClaimsDialect.kt` (same directory)
- Modify: `ClaimsDialectRegistry.kt` (add `displayNameFor`)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt:102`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/ClaimsDialectTest.kt`

**Interfaces:**
- Consumes: Task 1's `ClerkClaimsDialect`.
- Produces: `ClaimsDialect.displayName: String?`; `ClaimsDialectRegistry.displayNameFor(slug: String): String?` — Task 3 calls `displayNameFor` from `RouteModule`. Display names: `"Auth0"`, `"Clerk"`, `"WorkOS"`; `StandardClaimsDialect` and unknown slugs yield null.

- [ ] **Step 1: Write the failing tests**

In `ClaimsDialectRegistryTest`, add:

```kotlin
    @Test
    fun `display names are human-readable vendor brands`() {
        assertEquals("Auth0", registry.displayNameFor("auth0"))
        assertEquals("Clerk", registry.displayNameFor("clerk"))
        assertEquals("WorkOS", registry.displayNameFor("workos"))
    }

    @Test
    fun `plain oidc and unknown slugs have no display name`() {
        // Null lets the frontend fall back to its generic "single sign-on"
        // copy instead of rendering a raw config slug at the user.
        assertNull(registry.displayNameFor("oidc"))
        assertNull(registry.displayNameFor("typo-provider"))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.auth.ClaimsDialectRegistryTest'`
Expected: compile FAILURE — `unresolved reference: displayNameFor`

- [ ] **Step 3: Implement**

In `ClaimsDialect.kt`, add to the interface below `val id: String`:

```kotlin
    /**
     * Human-readable vendor name for UI copy ("Continue with Clerk").
     * Null when there is no brand to show; callers fall back to generic copy.
     */
    val displayName: String?
```

In each implementation, directly under `override val id: String = ID`:

```kotlin
    override val displayName: String? = "Auth0"      // Auth0ClaimsDialect
    override val displayName: String? = "Clerk"      // ClerkClaimsDialect
    override val displayName: String? = "WorkOS"     // WorkOsClaimsDialect
    override val displayName: String? = null         // StandardClaimsDialect
```

In `ClaimsDialectRegistry`, add below `forProvider`:

```kotlin
    /**
     * Display name for the login card. Unknown slugs return null — unlike
     * [forProvider] there is no fallback, because falling back would brand
     * the login card with a vendor we are not actually using.
     */
    fun displayNameFor(slug: String): String? = dialects.firstHandlerFor(ClaimsDialectId(slug))?.displayName
```

In `ServiceModule.kt:102`, replace:

```kotlin
            val providerLabel: String? = config.auth?.provider
```

with:

```kotlin
            val providerLabel: String? =
                config.auth?.provider?.let { ClaimsDialectRegistry.default().displayNameFor(it) }
```

(Import `ca.floo.roadtrip.service.auth.ClaimsDialectRegistry` if not present.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.auth.*' --tests 'ca.floo.roadtrip.service.settings.*'`
Expected: PASS. (`UserSettingsServiceTest` injects its own label string and is unaffected.)

- [ ] **Step 5: Align the spec's label-fallback wording**

The spec says unknown slugs "fall back to the raw slug"; the implementation deliberately returns null so the UI shows its generic copy instead of a raw config slug. In `docs/superpowers/specs/2026-08-01-clerk-auth-migration-design.md`, replace the line:

```
  standard/unknown → fall back to the raw slug).
```

with:

```
  standard/unknown → null, so the UI falls back to its generic copy).
```

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/auth \
        backend/src/main/kotlin/ca/floo/roadtrip/di/ServiceModule.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/auth/ClaimsDialectTest.kt \
        docs/superpowers/specs/2026-08-01-clerk-auth-migration-design.md
git commit -m "feat(auth): human-readable provider display names"
```

---

### Task 3: provider_label on /api/me

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/MeResponseDto.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt:126-153,169-176`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt:245-279`
- Test: existing web suites (`web/account/login-card.test.mjs` stubs `provider_label`; `web/account/login-card.js:98` already reads it)

**Interfaces:**
- Consumes: Task 2's `ClaimsDialectRegistry.displayNameFor(slug: String): String?`.
- Produces: `MeResponseDto.providerLabel` serialized as `provider_label`; `AuthRouteWiring.providerLabel: String?`. Frontend contract: `/api/me` now carries `provider_label` for both anonymous and authenticated callers (null when auth is disabled or the provider is unbranded).

- [ ] **Step 1: Add the DTO field**

In `MeResponseDto` (the outer class, after `isAuthEnabled`):

```kotlin
    /**
     * Human-readable identity-provider name for login UI copy ("Continue
     * with Clerk"). Null when auth is disabled or the provider is unbranded;
     * the frontend then falls back to its generic sign-in copy.
     */
    @SerialName("provider_label") val providerLabel: String? = null,
```

- [ ] **Step 2: Thread the label through the wiring**

In `AuthRoutes.kt`, add to `AuthRouteWiring`'s constructor (after `appRootUrl`):

```kotlin
    val providerLabel: String?,
```

Update `meResponse` to include it — the full function becomes:

```kotlin
private fun AuthRouteWiring.meResponse(principal: Principal.User): MeResponseDto {
    val user = userRepo.findById(principal.userId)
    return MeResponseDto(
        isAuthenticated = user != null,
        user =
            user?.let {
                MeUserDto(
                    id = it.id.value,
                    email = it.email,
                    displayName = it.displayName,
                    isEmailVerified = it.isEmailVerified,
                    roles = principal.roles.map { role -> role.wireValue },
                )
            },
        providerLabel = providerLabel,
    )
}
```

In the `/api/me` handler, the anonymous branch (currently `else -> call.respond(MeResponseDto(isAuthenticated = false))`) becomes:

```kotlin
                else -> call.respond(MeResponseDto(isAuthenticated = false, providerLabel = wiring.providerLabel))
```

(The `wiring == null` branch stays as-is: label null when auth is disabled.)

- [ ] **Step 3: Populate it in RouteModule**

In `authRouteWiring`, hoist the registry into a local and reuse it. Add just before `val oidcClient = OidcClient(...)`:

```kotlin
    val dialectRegistry = ClaimsDialectRegistry.default()
```

then replace the dialect line:

```kotlin
            claimsDialect = dialectRegistry.forProvider(authConfig.provider),
```

and in the returned `AuthRouteWiring`, after `appRootUrl = rootUrl,`:

```kotlin
        providerLabel = dialectRegistry.displayNameFor(authConfig.provider),
```

- [ ] **Step 4: Run backend and web tests**

Run: `./gradlew :backend:test`
Expected: PASS
Run: `node --test $(find web -name '*.test.mjs' | sort)`
Expected: PASS — `login-card.test.mjs` already exercises `me.provider_label` (stubs a resolved label and asserts it renders), so no frontend change is needed.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/MeResponseDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt web/account
git commit -m "fix(auth): surface provider_label on /api/me so the login card can brand itself"
```

---

### Task 4: Per-vendor credential config

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/AuthConfig.kt`
- Modify: `backend/src/main/resources/application.yaml:27-41`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/config/AuthConfigTest.kt`

**Interfaces:**
- Consumes: `ConfigSection.section(name)/value(name)/valueOrDefault/duration` (see `config/ConfigSection.kt`).
- Produces: unchanged `AuthConfig` data class (same fields — callers are unaffected); `fromConfig` now reads credentials from `providers.<active-slug>.*`. Config key contract for Task 5: env names per Global Constraints.

- [ ] **Step 1: Rewrite the failing tests**

Replace the body of `AuthConfigTest` with:

```kotlin
class AuthConfigTest {
    private val clerk =
        mapOf(
            "provider" to "clerk",
            "providers.clerk.issuer" to "https://clerk.example.com",
            "providers.clerk.client-id" to "client-clerk",
            "providers.clerk.client-secret" to "shh-clerk",
        )
    private val auth0 =
        mapOf(
            "providers.auth0.issuer" to "https://tenant.auth0.example.com",
            "providers.auth0.client-id" to "client-auth0",
            "providers.auth0.client-secret" to "shh-auth0",
        )

    private fun section(values: Map<String, String>) = ConfigSection(values.mapKeys { "roadtrip.auth.${it.key}" }).section("roadtrip.auth")

    @Test
    fun `the active vendor block parses`() {
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk)))

        assertEquals("https://clerk.example.com", config.issuer)
        assertEquals("client-clerk", config.clientId)
        assertEquals("shh-clerk", config.clientSecret)
        assertEquals("clerk", config.provider)
        assertEquals(Duration.ofDays(30), config.sessionTtl)
        assertTrue(config.isCookieSecure)
    }

    @Test
    fun `switching provider selects the other vendor's credentials`() {
        // The whole rollback story: both blocks stay configured, one value flips.
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk + auth0 + ("provider" to "auth0"))))

        assertEquals("auth0", config.provider)
        assertEquals("https://tenant.auth0.example.com", config.issuer)
        assertEquals("client-auth0", config.clientId)
        assertEquals("shh-auth0", config.clientSecret)
    }

    @Test
    fun `an incomplete active block means auth disabled even when the other vendor is complete`() {
        assertNull(AuthConfig.fromConfig(section(auth0 + ("provider" to "clerk"))))
        assertNull(AuthConfig.fromConfig(section(clerk - "providers.clerk.issuer")))
        assertNull(AuthConfig.fromConfig(section(clerk - "providers.clerk.client-id")))
        assertNull(AuthConfig.fromConfig(section(clerk + ("providers.clerk.issuer" to "   "))))
        assertNull(AuthConfig.fromConfig(section(emptyMap())))
    }

    @Test
    fun `a missing client secret means auth disabled, not a public client`() {
        // Confidential client doing a server-side code exchange; the login-flow
        // cookie's signing key derives from the secret.
        assertNull(AuthConfig.fromConfig(section(clerk - "providers.clerk.client-secret")))
        assertNull(AuthConfig.fromConfig(section(clerk + ("providers.clerk.client-secret" to "  "))))
    }

    @Test
    fun `a trailing slash on the issuer is stripped`() {
        // Otherwise discovery resolves to a doubled slash and 404s.
        val config = assertNotNull(AuthConfig.fromConfig(section(clerk + ("providers.clerk.issuer" to "https://clerk.example.com/"))))

        assertEquals("https://clerk.example.com", config.issuer)
    }

    @Test
    fun `ttl and cookie flag stay at the auth level, not per vendor`() {
        val config =
            assertNotNull(
                AuthConfig.fromConfig(
                    section(clerk + mapOf("session-ttl" to "12h", "cookie-secure" to "false")),
                ),
            )

        assertEquals(Duration.ofHours(12), config.sessionTtl)
        assertTrue(!config.isCookieSecure)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.config.AuthConfigTest'`
Expected: FAIL — `the active vendor block parses` returns null under the old flat-key reader

- [ ] **Step 3: Implement per-vendor selection**

In `AuthConfig.kt`, add alongside the other key constants:

```kotlin
private const val PROVIDERS_KEY = "providers"
```

Rewrite `fromConfig` (the `AuthConfig` data class fields are unchanged):

```kotlin
        fun fromConfig(config: ConfigSection): AuthConfig? {
            val provider = config.valueOrDefault(PROVIDER_KEY, DEFAULT_PROVIDER)
            // Credentials are per-vendor so both vendors stay configured at
            // once: switching (or rolling back) is the provider value alone,
            // never a credential swap. Only the ACTIVE block gates the
            // enabled/disabled decision.
            val vendor = config.section(PROVIDERS_KEY).section(provider)
            val issuer = vendor.value(ISSUER_KEY) ?: return null
            val clientId = vendor.value(CLIENT_ID_KEY) ?: return null
            // The secret is required, not optional: this is a confidential client
            // doing a server-side code exchange, and the flow cookie's signing key
            // is derived from it. A deployment without one is misconfigured, not a
            // public client.
            val clientSecret = vendor.value(CLIENT_SECRET_KEY) ?: return null
            return AuthConfig(
                // Trailing slash stripped so discovery resolves to
                // "$issuer/.well-known/openid-configuration" without doubling up.
                issuer = issuer.trimEnd('/'),
                clientId = clientId,
                clientSecret = clientSecret,
                provider = provider,
                sessionTtl = config.duration(SESSION_TTL_KEY, defaultSessionTtl),
                isCookieSecure = config.valueOrDefault(COOKIE_SECURE_KEY, COOKIE_SECURE_DEFAULT).toBoolean(),
            )
        }
```

Update the class KDoc paragraph that ends "Changing vendors is this value plus [issuer] and a credential pair." to:

```
 * [provider] selects a claims dialect and the matching credential block under
 * `providers.<slug>` — the flow itself is plain OIDC for every value. Both
 * vendors' credentials stay configured side by side, so changing vendors (or
 * rolling back) is this one value.
```

- [ ] **Step 4: Update application.yaml**

Replace the `auth:` block (lines 27-41) with:

```yaml
  auth:
    # Selects the active vendor block under `providers` and its claims
    # dialect — the flow is plain OIDC for every value. clerk | auth0 |
    # workos | oidc. See rfcs/0009-auth-provider-layer.md and
    # docs/superpowers/specs/2026-08-01-clerk-auth-migration-design.md.
    #
    # Both vendors stay configured so switching (or rolling back) is this one
    # value — no credential swapping. Defaults to the vendor we actually use.
    provider: "${AUTH_PROVIDER:clerk}"
    # Blank issuer or client-id in the ACTIVE block = auth disabled, a
    # first-class state: the app boots and every anonymous surface works with
    # no tenant provisioned.
    providers:
      auth0:
        issuer: "${AUTH_AUTH0_ISSUER:}"
        client-id: "${AUTH_AUTH0_CLIENT_ID:}"
        client-secret: "${AUTH_AUTH0_CLIENT_SECRET:}"
      clerk:
        issuer: "${AUTH_CLERK_ISSUER:}"
        client-id: "${AUTH_CLERK_CLIENT_ID:}"
        client-secret: "${AUTH_CLERK_CLIENT_SECRET:}"
    session-ttl: 30d
    cookie-secure: true
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew :backend:test`
Expected: PASS (full suite — `AuthConfigTest` plus everything downstream of `AppConfig`)

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/config/AuthConfig.kt \
        backend/src/main/resources/application.yaml \
        backend/src/test/kotlin/ca/floo/roadtrip/config/AuthConfigTest.kt
git commit -m "feat(config): per-vendor auth credential blocks selected by AUTH_PROVIDER"
```

---

### Task 5: Secrets registry, generated compose file, docs, CI comment

**Files:**
- Modify: `secrets/registry.yaml:71-85` (replace the three `ROADTRIP_AUTH_*` entries)
- Regenerate: `docker-compose.secrets.yml` via `./secrets/manage.py generate`
- Modify: `docs/secrets.md:108` (rotation prose names Auth0)
- Modify: `.github/workflows/deploy.yml:294-296,323-325` (comments only)

**Interfaces:**
- Consumes: env names from Global Constraints; Task 4's yaml expects exactly those names.
- Produces: vault/compose plumbing for all seven vars. Operator later runs `./secrets/manage.py set` for real values (not part of this plan).

- [ ] **Step 1: Replace the registry entries**

In `secrets/registry.yaml`, replace the `ROADTRIP_AUTH_ISSUER`, `ROADTRIP_AUTH_CLIENT_ID`, `ROADTRIP_AUTH_CLIENT_SECRET` entries (lines 71-85) with:

```yaml
AUTH_PROVIDER:
  description: Active identity vendor (clerk | auth0). Selects which AUTH_<VENDOR>_* block the backend uses; flipping this is the whole rollback story.
  consumers: [backend]

AUTH_CLERK_ISSUER:
  description: Clerk OIDC issuer URL (RFC 0009), e.g. https://clerk.roadtrip.floo.ca. Blank disables auth while clerk is active.
  consumers: [backend]
  required_in: [prod]

AUTH_CLERK_CLIENT_ID:
  description: Clerk OAuth application client id.
  consumers: [backend]
  required_in: [prod]

AUTH_CLERK_CLIENT_SECRET:
  description: Clerk OAuth application client secret. Also the HMAC key signing the login-flow cookie while clerk is active.
  consumers: [backend]
  required_in: [prod]

AUTH_AUTH0_ISSUER:
  description: Auth0 OIDC issuer URL, retained for one-flip rollback.
  consumers: [backend]

AUTH_AUTH0_CLIENT_ID:
  description: Auth0 OIDC client id, retained for one-flip rollback.
  consumers: [backend]

AUTH_AUTH0_CLIENT_SECRET:
  description: Auth0 OIDC client secret, retained for one-flip rollback. HMAC key for the login-flow cookie while auth0 is active.
  consumers: [backend]
```

If `./secrets/manage.py generate` (next step) rejects an entry lacking `required_in`, check how other optional entries in the file spell it (`grep -n 'required_in' secrets/registry.yaml`) and mirror that shape (`required_in: []` being the likely form).

- [ ] **Step 2: Regenerate the compose secrets file**

Run: `./secrets/manage.py generate`
Expected: `docker-compose.secrets.yml` now maps `auth_provider`, `auth_clerk_*`, `auth_auth0_*` secrets to the new env names; the `roadtrip_auth_*` entries are gone.
Verify: `grep -n 'AUTH_' docker-compose.secrets.yml` shows the seven new names and `grep ROADTRIP_AUTH docker-compose.secrets.yml` shows nothing.

- [ ] **Step 3: Update the rotation prose**

In `docs/secrets.md:108`, change `at Mapbox, Auth0, Slack…` to `at Mapbox, Clerk, Slack…`.

- [ ] **Step 4: Neutralize the deploy smoke-test comments**

In `.github/workflows/deploy.yml`, the comment above the "Verify sign-in handshake" step says `whenever the three auth secrets are merely *present*` — change to `whenever the active vendor's auth secrets are merely *present*`. The comment inside the failure branch reads `("Callback URL mismatch. <uri> is not in the list of allowed callback URLs")` — change that line to `(e.g. "Callback URL mismatch" prose; wording varies by vendor)`. The `curl`/`sed` mechanics stay untouched — they are already provider-neutral.

- [ ] **Step 5: Sanity-check nothing still references the retired names**

Run: `grep -rn 'ROADTRIP_AUTH' --exclude-dir=.git --exclude-dir=node_modules --exclude-dir='.claude' . | grep -v 'docs/superpowers' | grep -v 'rfcs/'`
Expected: no hits (`rfcs/` is historical record — mentions there stay).

- [ ] **Step 6: Commit**

```bash
git add secrets/registry.yaml docker-compose.secrets.yml docs/secrets.md .github/workflows/deploy.yml
git commit -m "chore(secrets): vendor-specific auth env vars; drop ROADTRIP_ prefix"
```

---

### Task 6: Full verification and ship

**Files:** none new — verification, push, PR.

**Interfaces:**
- Consumes: everything above.
- Produces: green build + draft PR with the operator rollout checklist.

- [ ] **Step 1: Full backend gate**

Run: `./gradlew :backend:test :backend:ktlintCheck :backend:detekt`
Expected: all PASS. Fix any ktlint/detekt findings in the new code (line-length and trailing-comma rules are enforced).

- [ ] **Step 2: Full web suite**

Run: `node --test $(find web -name '*.test.mjs' | sort)`
Expected: PASS

- [ ] **Step 3: Push and open draft PR**

```bash
git push -u origin worktree-audit-fixes
gh pr create --draft --title "Migrate auth provider from Auth0 to Clerk" --body "$(cat <<'EOF'
Points the vendor-agnostic OIDC auth layer (RFC 0009) at Clerk. No SDKs, no
DB migration; sessions survive; returning users relink by verified email.
Rollback = AUTH_PROVIDER=auth0.

Spec: docs/superpowers/specs/2026-08-01-clerk-auth-migration-design.md
Plan: docs/superpowers/plans/2026-08-01-clerk-auth-migration.md

## Operator checklist (before deploying)
- [ ] Clerk dashboard → Configure → OAuth applications → create app with
      scopes `openid email profile`, redirect URI
      `https://roadtrip.floo.ca/auth/callback`
- [ ] `./secrets/manage.py set` the values: AUTH_CLERK_ISSUER
      (https://clerk.roadtrip.floo.ca), AUTH_CLERK_CLIENT_ID,
      AUTH_CLERK_CLIENT_SECRET (shown once at creation!); move existing Auth0
      values to AUTH_AUTH0_*; optionally AUTH_PROVIDER
- [ ] After deploy: enable Clerk Test mode, sign in with a
      `+clerk_test` email (code 424242), confirm /auth/callback lands a
      session and user_identity gains a provider='clerk' row; verify
      email_verified arrived in the id_token (relink depends on it)
- [ ] Rotate the Clerk Backend API sk_live key (unused by this integration)

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 4: Report**

Summarize to the user: what changed, the one-flip rollback, and the operator checklist from the PR body.
