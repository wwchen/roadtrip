# Sandbox Deploys Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up a live, working endpoint for any git ref (PR or not) on demand, so an AI session or PR review can be accompanied by a running instance with a build-info banner and an "assume user" switcher.

**Architecture:** Three sequenced phases. **Backend** adds a `/api/build-info` endpoint and a sandbox impersonation wrap at the single `resolvePrincipal` seam. **Frontend** adds a top build-info banner and an assume-user switcher. **Infra/ops** publishes the backend image to GHCR by SHA, refactors the deploy into one parameterized script shared by prod/sandbox, adds a Postgres snapshot job, per-sandbox `sandbox_up.sh`/`sandbox_down.sh` scripts (Caddy wildcard routing + fresh user seed), `make` targets, a `/sandbox` comment workflow gated to OWNER/COLLABORATOR, and a TTL reaper.

**Tech Stack:** Kotlin/Ktor/Koin backend (`kotlinx.serialization`, jOOQ, Flyway), `kotlin.test` on JUnit 5, Ktor `testApplication`; dependency-free vanilla JS frontend (`web/`, no build step); docker-compose; GitHub Actions; Caddy; Cloudflare Tunnel; bash.

## Global Constraints

- **Backend layering:** `routes -> service -> repo`. SQL/jOOQ only in `repo`. No hand-built JSON in routes — use `@Serializable` DTOs. (AGENTS.md)
- **No inline magic constants.** Extract literals to named `const val` or env-driven config. (AGENTS.md)
- **Every route MUST end with `.access(RouteAccess.<level>)`** or the app fails at boot (completeness guard `RouteModule.kt:139-145`).
- **JDK toolchain:** `jvmToolchain(21)`; do NOT export `JAVA_HOME` — Gradle provisions its own JDK. `gradlew` is at repo root. (memory)
- **New DB tables/columns for jOOQ** must be added to `database.includes` allowlist in `build.gradle.kts` or codegen silently skips them. (memory) — *This plan seeds existing tables (`app_user`, `user_role`); no new tables, so no allowlist change.*
- **CI runs ktlint separately from test.** Run `make install-hooks` so the committed `.githooks/pre-commit` gate applies. If `:backend:test` hangs locally, push with `SKIP_PREPUSH=1` and read the PR checks. (memory)
- **Backend base package:** `ca.floo.roadtrip`.
- **`Role` enum has only `ADMIN`.** A regular user is `Principal.User(id, roles = emptySet())`.
- **Impersonation must be impossible in prod:** the sandbox branch is only reachable when `authWiring == null` (auth off) AND a dedicated flag is set; it only ever constructs `Principal.User` (never `Principal.System`); anything unexpected → `Principal.Anonymous`.

---

## File Structure

**Phase A — Backend**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BuildInfoDto.kt` — the `@Serializable` response DTO.
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/config/BuildInfoConfig.kt` — env-driven `{ env, sha, branch }`.
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/config/SandboxConfig.kt` — the `assumeUser` enable flag.
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/BuildInfoRoutes.kt` — `GET /api/build-info`.
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/SandboxPrincipal.kt` — parse sentinel token → `Principal.User`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/AppConfig.kt` — add `buildInfo` + `sandbox` properties.
- Modify: `backend/src/main/resources/application.yaml` — declare `build-info` + `sandbox` config with `${ENV:default}`.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt` — wire build-info route; wrap `resolvePrincipal` with the sandbox branch.
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt` — `/api/me` reflects the ambient principal when auth is off.
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/api/BuildInfoRoutesTest.kt`, `service/auth/SandboxPrincipalTest.kt`, and an addition to the `/api/me` test.

**Phase B — Frontend** (`web/`, vanilla JS, no build step; served by backend at `/web/*`)
- Create: `web/sandbox-banner.js` — fetch `/api/build-info`, render top bar when `env === "sandbox"`.
- Create: `web/sandbox-user-switcher.js` — render when `/api/me` reports `auth_enabled=false` and sandbox users exist; set the session cookie sentinel.
- Modify: `index.html`, `availability.html`, `watches.html` — include the two scripts.
- Modify: shared stylesheet — banner + switcher styles (find it in step).
- Test: `web/sandbox-banner.test.js`, `web/sandbox-user-switcher.test.js` (node:test, matching existing `web/` test style).

**Phase C — Infra/ops**
- Modify: `.github/workflows/ci.yml` — tag + push `roadtrip/backend` to GHCR by SHA.
- Create: `scripts/deploy.sh` — parameterized `deploy(env, ref, name)` core (extracted from the `make run env=prod` + deploy.yml SSH body).
- Create: `scripts/sandbox_up.sh`, `scripts/sandbox_down.sh` — sandbox wrappers (port alloc, Caddy vhost, user seed).
- Create: `scripts/sandbox_seed_users.sql` — fresh Will/Matt seed.
- Create: `scripts/sandbox_snapshot.sh` — nightly `pg_dump` of the seeded catalog.
- Create: `scripts/sandbox_reap.sh` — TTL teardown.
- Create: `docker-compose.sandbox.yml` — the minimal `postgres` + `backend` overlay.
- Create: `.github/workflows/sandbox.yml` — `/sandbox` comment trigger, OWNER/COLLABORATOR gate.
- Modify: `Makefile` — `sandbox` / `sandbox-stop` targets.
- Create: `docs/sandbox-deploys.md` — operator runbook.

> **Scope note:** Phase C is shell/CI/infra and is verified by smoke runs, not unit tests. It is the largest phase; if you prefer, it can be split into its own follow-up plan/PR after Phases A–B merge. The tasks below keep A→B→C order so each phase is independently shippable.

---

## Phase A — Backend

### Task A1: `/api/build-info` endpoint

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/BuildInfoDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/config/BuildInfoConfig.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/BuildInfoRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/AppConfig.kt` (add `buildInfo` property + populate in `fromProperties`)
- Modify: `backend/src/main/resources/application.yaml` (declare `build-info` section)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt` (wire route into `routing { }`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/api/BuildInfoRoutesTest.kt`

**Interfaces:**
- Produces: `BuildInfoDto(env: String, sha: String, branch: String)` (`@Serializable`); `BuildInfoConfig(env, sha, branch)` with `fun fromConfig(section: ConfigSection): BuildInfoConfig`; `Route.buildInfoRoutes(config: BuildInfoConfig)`; `AppConfig.buildInfo: BuildInfoConfig`.
- Consumes: existing `ConfigSection` (`value`/`valueOrDefault`), `RouteAccess.Anonymous`, `.describeApi`, `.access` from `ca.floo.roadtrip.route.common`.

- [ ] **Step 1: Write the failing test**

```kotlin
// BuildInfoRoutesTest.kt
package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.config.BuildInfoConfig
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class BuildInfoRoutesTest {
    @Test
    fun `GET build-info returns env sha branch`() =
        testApplication {
            application {
                routing {
                    buildInfoRoutes(BuildInfoConfig(env = "sandbox", sha = "abc1234", branch = "fix-foo"))
                }
            }
            val resp = client.get("/api/build-info")
            assertEquals(HttpStatusCode.OK, resp.status)
            val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
            assertEquals("sandbox", obj["env"]!!.jsonPrimitive.content)
            assertEquals("abc1234", obj["sha"]!!.jsonPrimitive.content)
            assertEquals("fix-foo", obj["branch"]!!.jsonPrimitive.content)
        }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.BuildInfoRoutesTest'`
Expected: FAIL — `buildInfoRoutes` / `BuildInfoConfig` unresolved reference.

- [ ] **Step 3: Create the DTO**

```kotlin
// BuildInfoDto.kt
package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

/** Identifies the running build. Surfaced by the sandbox banner; safe to expose. */
@Serializable
data class BuildInfoDto(
    val env: String,
    val sha: String,
    val branch: String,
)
```

- [ ] **Step 4: Create the config**

```kotlin
// BuildInfoConfig.kt
package ca.floo.roadtrip.config

/** Deploy-time build identity, injected via env vars (see application.yaml). */
data class BuildInfoConfig(
    val env: String,
    val sha: String,
    val branch: String,
) {
    companion object {
        private const val ENV_KEY = "env"
        private const val SHA_KEY = "sha"
        private const val BRANCH_KEY = "branch"
        private const val DEFAULT_ENV = "local"
        private const val UNKNOWN = "unknown"

        fun fromConfig(config: ConfigSection): BuildInfoConfig =
            BuildInfoConfig(
                env = config.valueOrDefault(ENV_KEY, DEFAULT_ENV),
                sha = config.valueOrDefault(SHA_KEY, UNKNOWN),
                branch = config.valueOrDefault(BRANCH_KEY, UNKNOWN),
            )
    }
}
```

- [ ] **Step 5: Create the route** (mirror `route/api/HealthRoutes.kt` idiom verbatim)

```kotlin
// BuildInfoRoutes.kt
package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.config.BuildInfoConfig
import ca.floo.roadtrip.model.api.BuildInfoDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

internal fun Route.buildInfoRoutes(config: BuildInfoConfig) {
    route("/api") {
        get("/build-info") {
            call.respond(BuildInfoDto(env = config.env, sha = config.sha, branch = config.branch))
        }.describeApi("build-info", "Identify the running build (env, sha, branch)")
            .access(RouteAccess.Anonymous)
    }
}
```

> Note: confirm the exact `RouteAccess` import path and the `.describeApi`/`.access` signatures against `route/api/HealthRoutes.kt` (imports from `ca.floo.roadtrip.route.common`). Adjust to match verbatim.

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.BuildInfoRoutesTest'`
Expected: PASS.

- [ ] **Step 7: Wire config + route into the app**

In `application.yaml`, under the existing `roadtrip:` tree, add:

```yaml
    build-info:
        env: "${ROADTRIP_BUILD_ENV:local}"
        sha: "${ROADTRIP_BUILD_SHA:unknown}"
        branch: "${ROADTRIP_BUILD_BRANCH:unknown}"
```

In `AppConfig.kt`, add `val buildInfo: BuildInfoConfig` to the data class and populate in `fromProperties`:

```kotlin
                buildInfo = BuildInfoConfig.fromConfig(roadtrip.section("build-info")),
```

In `RouteModule.kt`, inside `routing { ... }` (alongside `healthRoutes(readiness)` near line 127):

```kotlin
            buildInfoRoutes(config.buildInfo)
```

Add the import `import ca.floo.roadtrip.route.api.buildInfoRoutes`.

- [ ] **Step 8: Run the route tests + compile**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.api.*'`
Expected: PASS. (The `RouteModule` completeness guard confirms `.access` at boot.)

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/BuildInfoDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/config/BuildInfoConfig.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/api/BuildInfoRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/config/AppConfig.kt \
        backend/src/main/resources/application.yaml \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/api/BuildInfoRoutesTest.kt
git commit -m "feat(backend): add /api/build-info endpoint"
```

---

### Task A2: Sandbox impersonation at the `resolvePrincipal` seam

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/config/SandboxConfig.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/service/auth/SandboxPrincipal.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/config/AppConfig.kt` (add `sandbox` property)
- Modify: `backend/src/main/resources/application.yaml` (declare `sandbox` section)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt` (wrap `resolvePrincipal`; inject `UserRepo`)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/auth/SandboxPrincipalTest.kt`

**Interfaces:**
- Consumes: `Principal` (`Anonymous`/`User`/`System`), `UserId(value: Long)`, `Role.ADMIN`, `UserRepo.findById(id: UserId): UserRepo.User?` (the mapped `User` has `val id: UserId` and its roles come from `rolesFor(id)`). `resolvePrincipal: (String?) -> Principal` — the token is the session-cookie value.
- Produces: `SandboxConfig(assumeUserEnabled: Boolean)` with `fun fromConfig(section: ConfigSection): SandboxConfig`; `const val SANDBOX_TOKEN_PREFIX = "sandbox:"`; `fun sandboxPrincipal(token: String?, loadUser: (UserId) -> Set<Role>?): Principal`; `AppConfig.sandbox: SandboxConfig`.

**Design of the seam (why a cookie sentinel, not a header):** production wiring is `resolvePrincipal = { token -> authWiring?.authController?.resolve(token) ?: Principal.Anonymous }` (`RouteModule.kt:100`), where `token` is `call.request.sessionToken()`. The lambda receives only the token, so impersonation reuses that path: the switcher sets the session cookie to a sentinel `sandbox:<userId>`, and the sandbox branch (reached only when `authWiring == null`) parses it. **Safety gate #1 (auth off) is structural** — the branch cannot run when a real `AuthConfig` is present.

- [ ] **Step 1: Write the failing test**

```kotlin
// SandboxPrincipalTest.kt
package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserId
import kotlin.test.Test
import kotlin.test.assertEquals

class SandboxPrincipalTest {
    private val roles: Map<Long, Set<Role>> = mapOf(1L to setOf(Role.ADMIN), 2L to emptySet())
    private fun load(id: UserId): Set<Role>? = roles[id.value]

    @Test
    fun `valid sentinel yields admin User`() {
        assertEquals(Principal.User(UserId(1L), setOf(Role.ADMIN)), sandboxPrincipal("sandbox:1", ::load))
    }

    @Test
    fun `regular user has empty roles`() {
        assertEquals(Principal.User(UserId(2L), emptySet()), sandboxPrincipal("sandbox:2", ::load))
    }

    @Test
    fun `unknown user id yields Anonymous`() {
        assertEquals(Principal.Anonymous, sandboxPrincipal("sandbox:999", ::load))
    }

    @Test
    fun `missing token yields Anonymous`() {
        assertEquals(Principal.Anonymous, sandboxPrincipal(null, ::load))
    }

    @Test
    fun `non-sentinel token yields Anonymous`() {
        assertEquals(Principal.Anonymous, sandboxPrincipal("real-session-token", ::load))
    }

    @Test
    fun `malformed id yields Anonymous`() {
        assertEquals(Principal.Anonymous, sandboxPrincipal("sandbox:notanumber", ::load))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.auth.SandboxPrincipalTest'`
Expected: FAIL — `sandboxPrincipal` unresolved reference.

- [ ] **Step 3: Implement `sandboxPrincipal`**

```kotlin
// SandboxPrincipal.kt
package ca.floo.roadtrip.service.auth

import ca.floo.roadtrip.model.domain.auth.Principal
import ca.floo.roadtrip.model.domain.auth.Role
import ca.floo.roadtrip.model.domain.auth.UserId

/** Session-cookie sentinel the sandbox switcher sets: "sandbox:<userId>". */
const val SANDBOX_TOKEN_PREFIX = "sandbox:"

/**
 * Maps a sandbox sentinel token to a [Principal.User], or [Principal.Anonymous]
 * for anything unexpected. Only ever constructs [Principal.User] — never
 * [Principal.System]. Callers MUST only invoke this when auth is disabled.
 *
 * @param loadUser returns the user's roles, or null if the user does not exist.
 */
fun sandboxPrincipal(token: String?, loadUser: (UserId) -> Set<Role>?): Principal {
    if (token == null || !token.startsWith(SANDBOX_TOKEN_PREFIX)) return Principal.Anonymous
    val id = token.removePrefix(SANDBOX_TOKEN_PREFIX).toLongOrNull() ?: return Principal.Anonymous
    val roles = loadUser(UserId(id)) ?: return Principal.Anonymous
    return Principal.User(UserId(id), roles)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.auth.SandboxPrincipalTest'`
Expected: PASS (all six cases).

- [ ] **Step 5: Create `SandboxConfig` + declare config**

```kotlin
// SandboxConfig.kt
package ca.floo.roadtrip.config

/** Sandbox-only switches. Must be off (default) in every real deployment. */
data class SandboxConfig(
    val assumeUserEnabled: Boolean,
) {
    companion object {
        private const val ASSUME_USER_KEY = "assume-user"
        private const val DEFAULT = "false"

        fun fromConfig(config: ConfigSection): SandboxConfig =
            SandboxConfig(
                assumeUserEnabled = config.valueOrDefault(ASSUME_USER_KEY, DEFAULT).toBoolean(),
            )
    }
}
```

In `application.yaml`, under `roadtrip:`:

```yaml
    sandbox:
        assume-user: "${ROADTRIP_SANDBOX_ASSUME_USER:false}"
```

In `AppConfig.kt`, add `val sandbox: SandboxConfig` and populate:

```kotlin
                sandbox = SandboxConfig.fromConfig(roadtrip.section("sandbox")),
```

- [ ] **Step 6: Wrap `resolvePrincipal` in `RouteModule.kt`**

Inject `UserRepo` alongside the existing injections (near `RouteModule.kt:76-77`):

```kotlin
    val userRepo: UserRepo by inject()
```

Replace the `install(roadtripAuthorization)` block (`RouteModule.kt:98-101`) with:

```kotlin
    val authWiring = authRouteWiring(ctx, config)
    install(roadtripAuthorization) {
        resolvePrincipal = { token ->
            when {
                authWiring != null -> authWiring.authController.resolve(token) ?: Principal.Anonymous
                // Auth off. Only here can the sandbox sentinel be honored — a real
                // AuthConfig makes authWiring non-null and this branch unreachable.
                config.sandbox.assumeUserEnabled ->
                    sandboxPrincipal(token) { id -> userRepo.findById(id)?.roles }
                else -> Principal.Anonymous
            }
        }
    }
```

Add imports: `import ca.floo.roadtrip.service.auth.sandboxPrincipal`, `import ca.floo.roadtrip.repo.UserRepo`.

> Confirm `UserRepo.User` exposes a `roles: Set<Role>` field (the `findById` mapper builds it from `rolesFor(id)` at `UserRepo.kt:37-43`). If the field is named differently, use that name. Confirm `UserRepo` is Koin-registered (check `di/RepoModule.kt`); if not directly injectable, obtain it the way `authRouteWiring` already does.

- [ ] **Step 7: Run tests + compile**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.auth.*'` then `./gradlew :backend:compileKotlin`
Expected: PASS + compiles.

- [ ] **Step 8: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/config/SandboxConfig.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/service/auth/SandboxPrincipal.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/config/AppConfig.kt \
        backend/src/main/resources/application.yaml \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/service/auth/SandboxPrincipalTest.kt
git commit -m "feat(backend): sandbox user impersonation at resolvePrincipal seam"
```

---

### Task A3: `/api/me` reflects the ambient principal when auth is off

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt` (`/api/me` handler ~line 124-153)
- Test: the `/api/me` test file (add a case)

**Why:** the switcher needs `/api/me` to report the assumed user. Today `/api/me` short-circuits to `isAuthEnabled=false, isAuthenticated=false` when `wiring == null`, ignoring the ambient principal. Change it so that when `wiring == null` it still reads `call.principal()` and reports a `Principal.User` if present (from the sandbox sentinel), while keeping `isAuthEnabled=false`.

**Interfaces:**
- Consumes: `call.principal()` (`route/common/RouteAccessDsl.kt:27`, returns `Principal`, default `Anonymous`); `MeResponseDto(isAuthenticated, user, isAuthEnabled)` (`@SerialName`s `authenticated`/`auth_enabled`), `MeUserDto`; `userRepo.findById`.

- [ ] **Step 1: Write the failing test** (mirror `SettingsRoutesTest` install pattern)

```kotlin
@Test
fun `GET me with sandbox principal and auth off reports user but auth disabled`() =
    testApplication {
        application {
            install(roadtripAuthorization) {
                resolvePrincipal = { token ->
                    if (token == "sandbox:1") Principal.User(UserId(1L), setOf(Role.ADMIN)) else Principal.Anonymous
                }
            }
            routing { authRoutes(wiring = null, userRepo = stubUserRepoReturning(UserId(1L))) }
        }
        val resp = client.get("/api/me") { header(HttpHeaders.Cookie, "$SESSION_COOKIE=sandbox:1") }
        val obj = Json.parseToJsonElement(resp.bodyAsText()).jsonObject
        assertEquals(false, obj["auth_enabled"]!!.jsonPrimitive.boolean)
        assertEquals(true, obj["authenticated"]!!.jsonPrimitive.boolean)
    }
```

> Match the real `authRoutes(...)` signature. Today it takes `wiring`; the auth-off branch now needs a `userRepo` to build `MeUserDto`, so add a `userRepo` parameter (or thread it via the wiring type). Use the existing test's stub/fake repo approach (see `FakeUserRepo` referenced in `UserSettingsServiceTest.kt`). Confirm `SESSION_COOKIE` import from `route/auth/AuthCookies`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests '*AuthRoutesTest*'`
Expected: FAIL — currently returns `authenticated=false` for the auth-off case.

- [ ] **Step 3: Update the `/api/me` handler**

Replace the `if (wiring == null)` short-circuit (`AuthRoutes.kt:126-129`) so it consults the ambient principal, and extract the user→DTO mapping into a shared helper reused by both branches (DRY):

```kotlin
        get("/me") {
            if (wiring == null) {
                // Auth off. Normally Anonymous, but a sandbox may have assumed a user
                // via the ambient principal. Report it, while keeping auth "disabled".
                return@get when (val principal = call.principal()) {
                    is Principal.User -> call.respond(meResponseForUser(userRepo, principal, isAuthEnabled = false))
                    else -> call.respond(MeResponseDto(isAuthenticated = false, isAuthEnabled = false))
                }
            }
            when (val principal = wiring.authController.resolve(call.request.sessionToken())) {
                is Principal.User -> call.respond(wiring.meResponse(principal))
                else -> call.respond(MeResponseDto(isAuthenticated = false))
            }
        }
```

Refactor the existing `AuthRouteWiring.meResponse` (`AuthRoutes.kt:138-153`) so its body delegates to a free function `meResponseForUser(userRepo, principal, isAuthEnabled = true)`. Add `import ca.floo.roadtrip.route.common.principal`. Thread `userRepo` into `authRoutes(...)` (and its call site in `RouteModule.kt:105`).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :backend:test --tests '*AuthRoutesTest*'`
Expected: PASS. Re-run the auth-on `/api/me` tests to confirm no regression.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/route/auth/AuthRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt
git commit -m "feat(backend): /api/me reflects sandbox-assumed user when auth off"
```

---

### Task A4: `GET /api/sandbox/users` — data-driven switcher source

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/api/SandboxUserDto.kt`
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/SandboxRoutes.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/UserRepo.kt` (add `listAll(): List<User>` — SQL in repo)
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt` (wire route)
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/api/SandboxRoutesTest.kt`

**Why:** the switcher lists seeded users. Rather than hardcode names in JS (the repo prefers data-driven; see "no hand-curated data" memory), expose the seeded `app_user` rows via a route gated on the sandbox flag.

**Interfaces:**
- Produces: `SandboxUserDto(id: Long, name: String, roles: List<String>)`; `Route.sandboxRoutes(config: SandboxConfig, userRepo: UserRepo)`; `UserRepo.listAll(): List<UserRepo.User>`.

- [ ] **Step 1: Write the failing test**

```kotlin
// SandboxRoutesTest.kt (abridged — two cases)
@Test
fun `lists users when sandbox enabled`() = testApplication {
    application { routing { sandboxRoutes(SandboxConfig(assumeUserEnabled = true), stubUserRepo(listOf(/* Will admin, Matt user */))) } }
    val resp = client.get("/api/sandbox/users")
    assertEquals(HttpStatusCode.OK, resp.status)
    // assert two entries, Will has "admin" in roles
}

@Test
fun `404 when sandbox disabled`() = testApplication {
    application { routing { sandboxRoutes(SandboxConfig(assumeUserEnabled = false), stubUserRepo(emptyList())) } }
    assertEquals(HttpStatusCode.NotFound, client.get("/api/sandbox/users").status)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :backend:test --tests '*SandboxRoutesTest*'`
Expected: FAIL — unresolved `sandboxRoutes`.

- [ ] **Step 3: Add `UserRepo.listAll()`** (SQL in repo, mirror `findById`)

```kotlin
    open fun listAll(): List<User> =
        ctx.select(APP_USER.fields().toList())
            .from(APP_USER)
            .fetch()
            .map { fromRecord(it, rolesFor(UserId(it.get(APP_USER.ID)!!))) }
```

> Confirm `fromRecord` visibility/signature and the `rolesFor` call shape against `UserRepo.kt`.

- [ ] **Step 4: Implement the DTO + route**

```kotlin
// SandboxUserDto.kt
package ca.floo.roadtrip.model.api

import kotlinx.serialization.Serializable

@Serializable
data class SandboxUserDto(val id: Long, val name: String, val roles: List<String>)
```

```kotlin
// SandboxRoutes.kt
package ca.floo.roadtrip.route.api

import ca.floo.roadtrip.config.SandboxConfig
import ca.floo.roadtrip.model.api.SandboxUserDto
import ca.floo.roadtrip.model.domain.auth.RouteAccess
import ca.floo.roadtrip.repo.UserRepo
import ca.floo.roadtrip.route.common.access
import ca.floo.roadtrip.route.common.describeApi
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

internal fun Route.sandboxRoutes(config: SandboxConfig, userRepo: UserRepo) {
    route("/api/sandbox") {
        get("/users") {
            if (!config.assumeUserEnabled) return@get call.respond(HttpStatusCode.NotFound)
            call.respond(
                userRepo.listAll().map {
                    SandboxUserDto(it.id.value, it.displayName ?: it.email, it.roles.map { r -> r.wireValue })
                },
            )
        }.describeApi("sandbox", "List seeded users the sandbox can assume")
            .access(RouteAccess.Anonymous)
    }
}
```

> Confirm `UserRepo.User` field names (`displayName`, `email`, `roles`). Adjust to match `UserRepo.kt:26-35`.

- [ ] **Step 5: Wire into `RouteModule` + run tests**

Add `sandboxRoutes(config.sandbox, userRepo)` in `routing { }`. Run: `./gradlew :backend:test --tests '*SandboxRoutesTest*'` → PASS.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/api/SandboxUserDto.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/route/api/SandboxRoutes.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/repo/UserRepo.kt \
        backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt \
        backend/src/test/kotlin/ca/floo/roadtrip/route/api/SandboxRoutesTest.kt
git commit -m "feat(backend): GET /api/sandbox/users lists seeded users when sandbox enabled"
```

---

## Phase B — Frontend

> `web/` is dependency-free vanilla JS served by the backend at `/web/*`; tests use `node:test`. Before writing: read one existing `web/*.js` + its `web/*.test.js` to match module style (ESM vs global), the fake-DOM test harness, and find the shared stylesheet the pages link. Put styles there, not inline.

### Task B1: Build-info banner

**Files:**
- Create: `web/sandbox-banner.js`
- Modify: `index.html`, `availability.html`, `watches.html` (include the script)
- Modify: shared CSS file (banner styles)
- Test: `web/sandbox-banner.test.js`

**Interfaces:**
- Consumes: `GET /api/build-info` → `{ env, sha, branch }`.
- Produces: `renderSandboxBanner(buildInfo, doc)` — inserts a fixed top bar into `doc` only when `buildInfo.env === "sandbox"`; returns the element or `null`. `initSandboxBanner(doc, fetchFn)` — fetch + render.

- [ ] **Step 1: Write the failing test** (adapt harness to match existing `web/*.test.js`)

```js
// sandbox-banner.test.js
import test from 'node:test';
import assert from 'node:assert/strict';
import { renderSandboxBanner } from './sandbox-banner.js';

function fakeDoc() {
  const mk = () => ({ children: [], attrs: {}, setAttribute(k, v) { this.attrs[k] = v; }, append(c) { this.children.push(c); }, set textContent(v) { this._t = v; }, get textContent() { return this._t; } });
  return { createElement: mk, body: mk() };
}

test('renders banner for sandbox env', () => {
  const doc = fakeDoc();
  const banner = renderSandboxBanner({ env: 'sandbox', sha: 'abc1234', branch: 'fix-foo' }, doc);
  assert.ok(banner);
  assert.equal(doc.body.children.length, 1);
});

test('renders nothing for prod env', () => {
  const doc = fakeDoc();
  const banner = renderSandboxBanner({ env: 'prod', sha: 'x', branch: 'master' }, doc);
  assert.equal(banner, null);
  assert.equal(doc.body.children.length, 0);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/sandbox-banner.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the banner**

```js
// sandbox-banner.js
const SANDBOX_ENV = 'sandbox';
const GITHUB_REPO_URL = 'https://github.com/wwchen/roadtrip';

export function renderSandboxBanner(buildInfo, doc = document) {
  if (!buildInfo || buildInfo.env !== SANDBOX_ENV) return null;
  const bar = doc.createElement('div');
  bar.setAttribute('class', 'sandbox-banner');
  bar.setAttribute('role', 'status');
  const env = doc.createElement('span');
  env.setAttribute('class', 'sandbox-banner__env');
  env.textContent = 'SANDBOX';
  const sha = doc.createElement('a');
  sha.setAttribute('href', `${GITHUB_REPO_URL}/commit/${buildInfo.sha}`);
  sha.textContent = buildInfo.sha;
  const branch = doc.createElement('span');
  branch.textContent = buildInfo.branch;
  bar.append(env);
  bar.append(sha);
  bar.append(branch);
  doc.body.append(bar);
  return bar;
}

export async function initSandboxBanner(doc = document, fetchFn = fetch) {
  try {
    const res = await fetchFn('/api/build-info');
    if (!res.ok) return;
    renderSandboxBanner(await res.json(), doc);
  } catch {
    /* build-info unavailable — no banner, never block the page */
  }
}

if (typeof document !== 'undefined') {
  initSandboxBanner();
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test web/sandbox-banner.test.js`
Expected: PASS.

- [ ] **Step 5: Add styles + include the script**

Add to the shared stylesheet:

```css
.sandbox-banner {
  position: fixed; top: 0; left: 0; right: 0; z-index: 9999;
  display: flex; gap: 1rem; align-items: center; justify-content: center;
  padding: 4px 12px; font: 12px/1.4 system-ui, sans-serif;
  background: #b45309; color: #fff;
}
.sandbox-banner__env { font-weight: 700; letter-spacing: .08em; }
.sandbox-banner a { color: #fff; text-decoration: underline; }
```

Include `<script type="module" src="/web/sandbox-banner.js"></script>` in `index.html`, `availability.html`, `watches.html` (match the existing script-include style/paths).

- [ ] **Step 6: Commit**

```bash
git add web/sandbox-banner.js web/sandbox-banner.test.js index.html availability.html watches.html
# plus the stylesheet path
git commit -m "feat(web): sandbox build-info banner"
```

---

### Task B2: Assume-user switcher

**Files:**
- Create: `web/sandbox-user-switcher.js`
- Modify: `index.html`, `availability.html`, `watches.html`
- Modify: shared CSS
- Test: `web/sandbox-user-switcher.test.js`

**Interfaces:**
- Consumes: `GET /api/me` → `{ auth_enabled, authenticated, user }`; `GET /api/sandbox/users` → `[{ id, name, roles }]`.
- Produces: `renderUserSwitcher(users, currentMe, doc, loc)`; selecting a user sets `doc.cookie = "<SESSION_COOKIE>=sandbox:<id>; path=/"` and reloads. `SESSION_COOKIE_NAME` const.

- [ ] **Step 1: Write the failing test**

```js
// sandbox-user-switcher.test.js
import test from 'node:test';
import assert from 'node:assert/strict';
import { renderUserSwitcher } from './sandbox-user-switcher.js';

function fakeDoc() {
  const mk = () => ({ children: [], attrs: {}, setAttribute(k, v) { this.attrs[k] = v; }, addEventListener(_e, h) { this.handler = h; }, append(c) { this.children.push(c); }, set textContent(v) { this._t = v; }, get textContent() { return this._t; } });
  return { cookie: '', createElement: mk, body: mk() };
}

test('renders nothing when auth enabled', () => {
  const el = renderUserSwitcher([{ id: 1, name: 'Will', roles: ['admin'] }], { auth_enabled: true }, fakeDoc(), { reload() {} });
  assert.equal(el, null);
});

test('lists seeded users when auth off', () => {
  const doc = fakeDoc();
  const el = renderUserSwitcher(
    [{ id: 1, name: 'Will', roles: ['admin'] }, { id: 2, name: 'Matt', roles: [] }],
    { auth_enabled: false }, doc, { reload() {} },
  );
  assert.ok(el);
  assert.equal(el.children.length, 2);
});

test('selecting a user sets the session cookie sentinel', () => {
  const doc = fakeDoc();
  let reloaded = false;
  const el = renderUserSwitcher([{ id: 2, name: 'Matt', roles: [] }], { auth_enabled: false }, doc, { reload() { reloaded = true; } });
  el.children[0].handler();
  assert.match(doc.cookie, /=sandbox:2/);
  assert.equal(reloaded, true);
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `node --test web/sandbox-user-switcher.test.js`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement the switcher**

```js
// sandbox-user-switcher.js
export const SESSION_COOKIE_NAME = 'roadtrip_session'; // confirm exact name from backend AuthCookies.kt
const ADMIN_ROLE = 'admin';

export function renderUserSwitcher(users, currentMe, doc = document, loc = window.location) {
  if (!currentMe || currentMe.auth_enabled !== false) return null; // only in auth-off sandboxes
  if (!Array.isArray(users) || users.length === 0) return null;
  const wrap = doc.createElement('div');
  wrap.setAttribute('class', 'sandbox-user-switcher');
  users.forEach((u) => {
    const btn = doc.createElement('button');
    btn.textContent = `${u.name}${Array.isArray(u.roles) && u.roles.includes(ADMIN_ROLE) ? ' (admin)' : ''}`;
    btn.addEventListener('click', () => {
      doc.cookie = `${SESSION_COOKIE_NAME}=sandbox:${u.id}; path=/`;
      loc.reload();
    });
    wrap.append(btn);
  });
  doc.body.append(wrap);
  return wrap;
}

export async function initUserSwitcher(doc = document, fetchFn = fetch) {
  try {
    const [meRes, usersRes] = await Promise.all([fetchFn('/api/me'), fetchFn('/api/sandbox/users')]);
    if (!meRes.ok || !usersRes.ok) return;
    renderUserSwitcher(await usersRes.json(), await meRes.json(), doc);
  } catch { /* not a sandbox / endpoint absent — no switcher */ }
}

if (typeof document !== 'undefined') { initUserSwitcher(); }
```

> Confirm the exact session cookie name from `route/auth/AuthCookies.kt` and use it verbatim.

- [ ] **Step 4: Run test to verify it passes**

Run: `node --test web/sandbox-user-switcher.test.js`
Expected: PASS.

- [ ] **Step 5: Styles + include the script**

Add switcher CSS to the shared stylesheet (fixed, below the banner). Include `<script type="module" src="/web/sandbox-user-switcher.js"></script>` in the three HTML pages.

- [ ] **Step 6: Commit**

```bash
git add web/sandbox-user-switcher.js web/sandbox-user-switcher.test.js index.html availability.html watches.html
# plus stylesheet
git commit -m "feat(web): sandbox assume-user switcher"
```

---

## Phase C — Infra / ops

> These tasks are shell/CI/compose and are verified by smoke runs, not unit tests. Keep host specifics behind variables (`SANDBOX_HOST`, `SANDBOX_TUNNEL_ZONE`, `SANDBOX_SECRET_KEY_PATH`, `SANDBOX_TTL_HOURS`, `SANDBOX_SNAPSHOT_PATH`, `SANDBOX_STATE_DIR`, `SANDBOX_CADDY_DIR`) so the tier can move off `mini-ca`. Where a Python helper is warranted, follow the repo's `scripts/*.py` + `scripts/test_*.py` pattern (e.g. `scripts/last_deployed_sha.py`). Every script starts `set -euo pipefail` with tunables as named vars at the top (no inline magic).

### Task C1: Publish backend image to GHCR by SHA (CI)

**Files:**
- Modify: `.github/workflows/ci.yml` (`docker-build` job, lines 265-317; `permissions` at line 19)
- Possibly modify: `scripts/test_ci_image_builds.py`

- [ ] **Step 1: Add GHCR login + tag + push** after the existing `Build backend image` step (keep `--load` intact):

```yaml
      - name: Log in to GHCR
        uses: docker/login-action@... # pin to a sha; match pinning style used in this repo
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}
      - name: Tag and push backend image by SHA
        run: |
          IMAGE="ghcr.io/${{ github.repository }}/backend"
          docker tag roadtrip/backend "$IMAGE:${{ github.sha }}"
          docker push "$IMAGE:${{ github.sha }}"
```

Add `packages: write` to the job (or workflow) `permissions`.

- [ ] **Step 2: Update `scripts/test_ci_image_builds.py`** if it pins the exact step list, so the push step is expected.
- [ ] **Step 3: Verify** by pushing and reading the PR check (per "use CI over local gradle hangs"). Expected: `docker-build` green; image at `ghcr.io/wwchen/roadtrip/backend:<sha>`.
- [ ] **Step 4: Commit**

```bash
git add .github/workflows/ci.yml scripts/test_ci_image_builds.py
git commit -m "ci: publish backend image to GHCR by SHA"
```

### Task C2: Compose overlay + `sandbox_up.sh` / `sandbox_down.sh` + user seed

**Files:**
- Create: `docker-compose.sandbox.yml` — `postgres` + `backend` only; backend image `ghcr.io/wwchen/roadtrip/backend:${SANDBOX_SHA}`; publishes `127.0.0.1:${SANDBOX_PORT}:8765`; env `ROADTRIP_PROFILE`, `ROADTRIP_BUILD_ENV=sandbox`, `ROADTRIP_BUILD_SHA`, `ROADTRIP_BUILD_BRANCH`, `ROADTRIP_SANDBOX_ASSUME_USER=true`, `ROADTRIP_AUTH_ISSUER=` (blank); project name `roadtrip-sb-${SANDBOX_NAME}`.
- Create: `scripts/sandbox_up.sh <ref> [name]`
- Create: `scripts/sandbox_down.sh <name>`
- Create: `scripts/sandbox_seed_users.sql`

- [ ] **Step 1: `docker-compose.sandbox.yml`** — two services derived from `docker-compose.yml`'s `backend`/`postgres`. Publish backend on a host-local port var; set the env above; auth blank.
- [ ] **Step 2: `sandbox_up.sh`** — `set -euo pipefail`; tunables at top. Resolve name: PR number if numeric ref else slugified branch. Allocate a free port from a fixed range. `docker compose -p roadtrip-sb-<name> -f docker-compose.sandbox.yml up -d`. Write a marker `${SANDBOX_STATE_DIR}/<name>.meta` with start-epoch + port (used by the reaper). Then: restore snapshot, seed users, write Caddy vhost, reload, health-check, print URL.
- [ ] **Step 3: `sandbox_seed_users.sql`** — fresh insert of Will (+ `user_role` ADMIN) and Matt (no role) into `app_user`/`user_role` with fixed ids. Never copies prod identities.
- [ ] **Step 4: DB prep** — `pg_restore` `${SANDBOX_SNAPSHOT_PATH}` into the sandbox Postgres, then apply `sandbox_seed_users.sql`.
- [ ] **Step 5: Caddy vhost** — write `${SANDBOX_CADDY_DIR}/sb-<name>.caddy` mapping `sb-<name>.${SANDBOX_TUNNEL_ZONE}` → `127.0.0.1:<port>`; reload Caddy.
- [ ] **Step 6: `sandbox_down.sh`** — `docker compose -p roadtrip-sb-<name> down -v`; remove the Caddy snippet + marker; reload Caddy.
- [ ] **Step 7: Smoke-test** on the dev box: `SANDBOX_SHA=<known-ghcr-sha> scripts/sandbox_up.sh <branch> test1`; curl the printed URL `/api/build-info` (expect `env=sandbox`); open the map; use the switcher; `scripts/sandbox_down.sh test1`; confirm project+volume+vhost+marker gone.
- [ ] **Step 8: Commit**

```bash
git add docker-compose.sandbox.yml scripts/sandbox_up.sh scripts/sandbox_down.sh scripts/sandbox_seed_users.sql
git commit -m "feat(sandbox): compose overlay + up/down scripts with Caddy routing and user seed"
```

### Task C3: Snapshot job + reaper

**Files:**
- Create: `scripts/sandbox_snapshot.sh` — `pg_dump -Fc` the seeded catalog to `${SANDBOX_SNAPSHOT_PATH}` (atomic: dump to `.tmp`, then `mv`). Runs on a schedule.
- Create: `scripts/sandbox_reap.sh` — for each `roadtrip-sb-*` project whose `${SANDBOX_STATE_DIR}/<name>.meta` start-epoch is older than `SANDBOX_TTL_HOURS` (default 24), call `sandbox_down.sh <name>`.

- [ ] **Step 1: `sandbox_snapshot.sh`** (atomic dump). Verify: run once; confirm it restores cleanly into a throwaway Postgres.
- [ ] **Step 2: `sandbox_reap.sh`**; verify with a marker backdated past the TTL — it tears that sandbox down and leaves fresh ones running.
- [ ] **Step 3: Document the schedule** (cron/systemd timer entries) in `docs/sandbox-deploys.md` (created in C7).
- [ ] **Step 4: Commit**

```bash
git add scripts/sandbox_snapshot.sh scripts/sandbox_reap.sh
git commit -m "feat(sandbox): nightly snapshot job + TTL reaper"
```

### Task C4: `make sandbox` / `make sandbox-stop`

**Files:**
- Modify: `Makefile` (targets + `.PHONY`)

- [ ] **Step 1: Add targets**

```makefile
.PHONY: sandbox sandbox-stop

sandbox:
	SANDBOX_SHA=$(or $(SHA),$(shell git rev-parse HEAD)) scripts/sandbox_up.sh $(or $(REF),$(shell git rev-parse --abbrev-ref HEAD)) $(NAME)

sandbox-stop:
	scripts/sandbox_down.sh $(NAME)
```

- [ ] **Step 2: Verify** `make sandbox REF=<branch>` prints a URL; `make sandbox-stop NAME=<name>` tears down.
- [ ] **Step 3: Commit**

```bash
git add Makefile
git commit -m "feat(sandbox): make sandbox / sandbox-stop CLI targets"
```

### Task C5: `.github/workflows/sandbox.yml` — `/sandbox` comment trigger

**Files:**
- Create: `.github/workflows/sandbox.yml`

- [ ] **Step 1: Workflow skeleton** with the OWNER/COLLABORATOR gate:

```yaml
name: Sandbox
on:
  issue_comment:
    types: [created]
permissions:
  pull-requests: write
jobs:
  sandbox:
    if: >
      github.event.issue.pull_request != null &&
      (github.event.comment.author_association == 'OWNER' ||
       github.event.comment.author_association == 'COLLABORATOR') &&
      startsWith(github.event.comment.body, '/sandbox')
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      # 1. resolve PR head SHA (actions/github-script or gh api)
      # 2. wait for GHCR image tag <sha> to exist; fail loudly if CI hasn't pushed it
      # 3. join Tailscale (mirror deploy.yml's tailscale step + OAuth secrets)
      # 4. Configure SSH (mirror deploy.yml: DEPLOY_SSH_KEY, DEPLOY_KNOWN_HOSTS)
      # 5. ssh $SANDBOX_HOST running SANDBOX_SHA=<sha> scripts/sandbox_up.sh <sha> pr-<number>
      #    (or scripts/sandbox_down.sh pr-<number> when body is "/sandbox stop")
      # 6. gh pr comment <number> --body "<url>"
```

- [ ] **Step 2: Fill each step** by mirroring `deploy.yml`'s Tailscale + `Configure SSH` + `Deploy on mini-ca` blocks verbatim (same secrets). Parse `/sandbox stop` vs `/sandbox` from `github.event.comment.body`.
- [ ] **Step 3: Verify** — comment `/sandbox` on a test PR as OWNER → URL comment appears; the `if` shows non-collaborators get no job run; `/sandbox stop` tears down.
- [ ] **Step 4: Commit**

```bash
git add .github/workflows/sandbox.yml
git commit -m "ci(sandbox): /sandbox comment trigger gated to OWNER/COLLABORATOR"
```

### Task C6: `scripts/deploy.sh` — unify the deploy seam (partial consolidation)

**Files:**
- Create: `scripts/deploy.sh` — `deploy <env> <ref> [name]`: resolve image (GHCR by SHA) · prepare DB · compose up (profiles per env) · register vhost (`direct`|`caddy-vhost`) · health-check.
- Modify: `scripts/sandbox_up.sh` to call `deploy.sh sandbox ...` so there is one core.

- [ ] **Step 1: Extract** the shared shape (image source, compose profiles, DB prep, routing mode `direct`|`caddy-vhost`, auth) into `deploy.sh`. Prod parameters keep `routing=direct` — **prod ingress is not changed**.
- [ ] **Step 2: Rewire** `sandbox_up.sh` to delegate to `deploy.sh sandbox ...`. Leave `make run env=prod` as-is; the unified path is proven on sandbox first (a follow-up can migrate prod to `deploy.sh prod`).
- [ ] **Step 3: Verify** a sandbox still spins up via the unified script (repeat C2 step-7 smoke). Confirm `make run env=prod` behavior is untouched.
- [ ] **Step 4: Commit**

```bash
git add scripts/deploy.sh scripts/sandbox_up.sh
git commit -m "refactor(deploy): unify sandbox behind one parameterized deploy seam"
```

### Task C7: Operator runbook

**Files:**
- Create: `docs/sandbox-deploys.md`
- Modify: `README.md` (link) and `docs/reservation-providers.md`-style index if one exists

- [ ] **Step 1: Document** trigger commands (`/sandbox`, `/sandbox stop`, `make sandbox`), host variables, the snapshot + reaper schedule, the Caddy wildcard + Cloudflare tunnel setup, and how to move the tier to a dedicated host.
- [ ] **Step 2: Commit**

```bash
git add docs/sandbox-deploys.md README.md
git commit -m "docs(sandbox): operator runbook"
```

---

## Self-Review

**Spec coverage:**
- Trigger (comment + CLI, one script) → C5, C4, C2/C6. ✅
- OWNER/COLLABORATOR gate → C5. ✅
- Unified `deploy()` seam (partial, routing pluggable) → C6. ✅
- GHCR by SHA → C1. ✅
- Caddy wildcard routing → C2. ✅
- Snapshot DB + fresh Will/Matt seed → C3, C2. ✅
- Auth off + impersonation, fail-closed gates → A2 (structural auth-off gate, User-only, Anonymous fallback), A3 (/api/me). ✅
- Build-info banner (env/sha/branch, sandbox-only) → A1, B1. ✅
- Assume-user switcher (data-driven) → A4, B2. ✅
- Reaper (TTL) → C3. ✅
- Host abstraction → C2/C6 variables, C7 docs. ✅
- Testing (impersonation seam, build-info, seed users, reaper, unauthorized comment) → A1–A4 tests, B1/B2 tests, C3/C5 verify steps. ✅

**Placeholder scan:** Backend/frontend tasks (A1–B2) carry real, runnable code. Infra tasks (C1–C7) give concrete scripts/config with explicit verify steps; the annotated `# n.` lines in C5 are a step list to fill by mirroring `deploy.yml` (not silent gaps) — acceptable for CI/shell, each task has a verification step.

**Type consistency:** `Principal.User(UserId, Set<Role>)`, `UserId(Long)`, `Role.ADMIN`/`.wireValue`, `sandboxPrincipal(String?, (UserId)->Set<Role>?)`, `SANDBOX_TOKEN_PREFIX="sandbox:"`, `BuildInfoConfig/Dto(env,sha,branch)`, `SandboxUserDto(id,name,roles)`, cookie sentinel `sandbox:<id>` — consistent across A2/A3/A4/B2. `/api/me` wire names (`authenticated`, `auth_enabled`) match `MeResponseDto` `@SerialName`s used in B2. Env var names (`ROADTRIP_BUILD_ENV/_SHA/_BRANCH`, `ROADTRIP_SANDBOX_ASSUME_USER`) match between `application.yaml` (A1/A2) and `docker-compose.sandbox.yml` (C2).

**Known verify-against-source follow-ups (flagged inline):** exact `RouteAccess`/`.access`/`.describeApi` imports (vs `HealthRoutes.kt`); `UserRepo.User` field names + `fromRecord`/`rolesFor` shapes (vs `UserRepo.kt:26-43`); Koin registration of `UserRepo`; exact session cookie name (`AuthCookies.kt`); `authRoutes(...)` signature for threading `userRepo`; existing `web/` module/test conventions + stylesheet path.
