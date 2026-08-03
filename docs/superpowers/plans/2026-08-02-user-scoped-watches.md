# User-scoped availability watches Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make availability watches belong to the signed-in user — anonymous callers get 401 and see no alerts UI; a user sees/manages only their own watches; admins see all.

**Architecture:** Add a non-null `owner_user_id` to `availability_watch` (deleting existing ownerless rows). Flip the five `/api/watches` routes to `RouteAccess.User`. Promote the existing repo-private `UserRepo.User` to a canonical domain `User` (with `isAdmin`); the watch controller takes the ambient `Principal.User`, resolves the domain `User`, stamps the owner on create, and scopes list/get/update/delete by owner (admins bypass). Frontend hides the topbar alerts panel on 401 and shows a sign-in empty state on the watches page, both re-refreshing on a new `roadtrip:auth-changed` event.

**Tech Stack:** Kotlin, Ktor, jOOQ, Flyway (Postgres), JUnit5 (`SharedDbTest`), vanilla JS with `node:test`.

## Global Constraints

- **No inline magic constants.** Extract literals to named `const val` / module consts (per `AGENTS.md`).
- **Layering `routes → service → repo`.** SQL/jOOQ only in repos. Routes own Ktor types and the `Principal`; services take domain types (`Principal.User`, `UserId`, domain `User`). Ownership *checks* live in the controller.
- **Ports stay non-leaky.** The Slack interactivity path calls `watchService` directly and must remain owner-check-free (it is a signed, non-user path).
- **jOOQ codegen** regenerates from migrations; `availability_watch` is already in `database.includes`. The new column appears automatically after codegen runs.
- **Role enum value is `Role.ADMIN`** (wire value `"admin"`).
- **Migration version:** next free is `V49` (highest existing is `V48`).
- **Frontend custom-event pattern:** mirror `web/availability/watch-events.js` (a `window` `CustomEvent`, try/catch guarded).
- Backend build uses Gradle toolchain 21 — do NOT export `JAVA_HOME`. Run backend tests via `./gradlew :backend:test`. If `:backend:test` hangs locally, push with `SKIP_PREPUSH=1` and read PR checks.
- Web tests run via `node --test $(find web -name '*.test.mjs')`.

---

## File Structure

**Backend — created:**
- `backend/src/main/resources/db/migration/V49__watch_owner.sql` — delete ownerless watches, add `owner_user_id`.
- `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/auth/User.kt` — promoted domain user model.

**Backend — modified:**
- `repo/UserRepo.kt` — return the domain `User` instead of the nested `UserRepo.User`.
- `service/settings/UserSettingsService.kt` + tests + `SandboxRoutesTest` / `AuthRoutesTest` / `RouteAccessCoverageTest` — import change from `UserRepo.User` → `User`.
- `repo/AvailabilityWatchRepo.kt` — `Watch.ownerUserId`, `CreateInput.ownerUserId`, `scopeConditions(ownerUserId)`, `list`/`count` params, `fromRecord` mapping, `create` sets the column.
- `service/availability/AvailabilityWatchService.kt` — `create(...)` gains `ownerUserId: UserId`.
- `service/availability/AvailabilityWatchController.kt` — every method takes `principal: Principal.User`; resolves domain `User` via `UserRepo`; owner scoping + cross-user 404.
- `route/api/availability/AvailabilityWatchRoutes.kt` — flip 5 routes to `RouteAccess.User`; `requireUser()` helper; thread principal.
- `di/RouteModule.kt` — wire `UserRepo` into the watch controller factory.

**Frontend:**
- `web/availability/auth-events.js` — **created**: `AUTH_CHANGED_EVENT`, `notifyAuthChanged()`, `onAuthChanged()`.
- `web/topbar/auth.js` — fire `notifyAuthChanged()` after `render`.
- `web/topbar/alerts.js` — hide panel on 401; subscribe to auth-changed.
- `web/watches/watches-page.js` — sign-in empty state on 401; subscribe to auth-changed.

---

## Task 1: Promote `UserRepo.User` to a domain `User`

Moves the existing repo-private record to `model/domain/auth/User.kt` and adds `isAdmin`, so watches (and everyone) thread one real object. Pure refactor — no behavior change.

**Files:**
- Create: `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/auth/User.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/UserRepo.kt`
- Modify (import only): `backend/src/main/kotlin/ca/floo/roadtrip/service/settings/UserSettingsService.kt`
- Modify (import only): `backend/src/test/kotlin/ca/floo/roadtrip/service/settings/UserSettingsServiceTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/api/SandboxRoutesTest.kt`, `backend/src/test/kotlin/ca/floo/roadtrip/route/common/RouteAccessCoverageTest.kt`

**Interfaces:**
- Produces: `ca.floo.roadtrip.model.domain.auth.User(id: UserId, email: String, displayName: String?, isEmailVerified: Boolean, status: UserStatus, roles: Set<Role>, createdAt: OffsetDateTime, updatedAt: OffsetDateTime)` with `val isAdmin: Boolean get() = Role.ADMIN in roles`. `UserRepo.findById/findByEmail/create/listSandboxUsers` now return this `User`.

- [ ] **Step 1: Write the failing test**

Create `backend/src/test/kotlin/ca/floo/roadtrip/model/domain/auth/UserTest.kt`:

```kotlin
package ca.floo.roadtrip.model.domain.auth

import java.time.OffsetDateTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserTest {
    private fun user(roles: Set<Role>) =
        User(
            id = UserId(1),
            email = "a@example.com",
            displayName = null,
            isEmailVerified = true,
            status = UserStatus.ACTIVE,
            roles = roles,
            createdAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
            updatedAt = OffsetDateTime.parse("2026-01-01T00:00:00Z"),
        )

    @Test
    fun `isAdmin is true when the admin role is present`() {
        assertTrue(user(setOf(Role.ADMIN)).isAdmin)
    }

    @Test
    fun `isAdmin is false without the admin role`() {
        assertFalse(user(emptySet()).isAdmin)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.model.domain.auth.UserTest'`
Expected: FAIL — `User` is unresolved.

- [ ] **Step 3: Create the domain model**

Create `backend/src/main/kotlin/ca/floo/roadtrip/model/domain/auth/User.kt`:

```kotlin
package ca.floo.roadtrip.model.domain.auth

import java.time.OffsetDateTime

/**
 * The `app_user` account record as a domain entity — distinct from
 * [Principal.User], which is the thin request-auth identity (id + roles).
 * Loaded by [ca.floo.roadtrip.repo.UserRepo] when a caller needs the account,
 * not just "who is calling".
 */
data class User(
    val id: UserId,
    val email: String,
    val displayName: String?,
    val isEmailVerified: Boolean,
    val status: UserStatus,
    val roles: Set<Role>,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
) {
    /** Convenience for the one coarse role we model today. */
    val isAdmin: Boolean get() = Role.ADMIN in roles
}
```

- [ ] **Step 4: Remove the nested type and point `UserRepo` at the domain `User`**

In `backend/src/main/kotlin/ca/floo/roadtrip/repo/UserRepo.kt`: delete the nested `data class User(...)` block and add `import ca.floo.roadtrip.model.domain.auth.User`. The `fromRecord(...)` construction and all method return types (`findById`, `findByEmail`, `create`, `listSandboxUsers`) stay identical — they now build the imported `User` (same field names and order).

- [ ] **Step 5: Fix references in dependent files**

Replace `UserRepo.User` with `User` (adding `import ca.floo.roadtrip.model.domain.auth.User`) in:
- `service/settings/UserSettingsService.kt`
- `test/.../service/settings/UserSettingsServiceTest.kt`
- `test/.../route/auth/AuthRoutesTest.kt`
- `test/.../route/api/SandboxRoutesTest.kt`
- `test/.../route/common/RouteAccessCoverageTest.kt`

Find any stragglers first:

```bash
grep -rln "UserRepo\.User" backend/src/main/kotlin backend/src/test/kotlin
```

- [ ] **Step 6: Run the affected tests**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.model.domain.auth.UserTest' --tests 'ca.floo.roadtrip.service.settings.*' --tests 'ca.floo.roadtrip.route.auth.AuthRoutesTest'`
Expected: PASS. If it hangs locally, push with `SKIP_PREPUSH=1` and read PR checks.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/model/domain/auth/User.kt backend/src/main/kotlin/ca/floo/roadtrip/repo/UserRepo.kt backend/src/main/kotlin/ca/floo/roadtrip/service/settings/UserSettingsService.kt backend/src/test/kotlin/ca/floo/roadtrip/model/domain/auth/UserTest.kt backend/src/test/kotlin/ca/floo/roadtrip/service/settings/UserSettingsServiceTest.kt backend/src/test/kotlin/ca/floo/roadtrip/route/auth/AuthRoutesTest.kt backend/src/test/kotlin/ca/floo/roadtrip/route/api/SandboxRoutesTest.kt backend/src/test/kotlin/ca/floo/roadtrip/route/common/RouteAccessCoverageTest.kt
git commit -m "refactor: promote UserRepo.User to a domain User with isAdmin"
```

---

## Task 2: Migration — add `owner_user_id`, delete ownerless watches

**Files:**
- Create: `backend/src/main/resources/db/migration/V49__watch_owner.sql`

**Interfaces:**
- Produces: `availability_watch.owner_user_id BIGINT NOT NULL REFERENCES app_user(id)`, index `availability_watch_owner_idx`. jOOQ `AVAILABILITY_WATCH.OWNER_USER_ID` after codegen.

- [ ] **Step 1: Write the migration**

Create `backend/src/main/resources/db/migration/V49__watch_owner.sql`:

```sql
-- Watches now belong to a user. Existing rows are ownerless and cannot be
-- assigned to anyone, so drop them. All child tables
-- (availability_watch_target, availability_watch_poller, availability_run/job)
-- reference availability_watch(id) ON DELETE CASCADE, so this clears the whole
-- subtree; the V30 last-target prune trigger is moot once the parent is gone.
DELETE FROM availability_watch;

ALTER TABLE availability_watch
  ADD COLUMN owner_user_id BIGINT NOT NULL REFERENCES app_user(id);

CREATE INDEX availability_watch_owner_idx ON availability_watch (owner_user_id);
```

- [ ] **Step 2: Regenerate jOOQ**

Run: `./gradlew :backend:generateJooq`
Expected: `AvailabilityWatch.kt` under `backend/build/generated/jooq/...` now has an `OWNER_USER_ID` field. Verify:

```bash
grep -n "OWNER_USER_ID" backend/build/generated/jooq/main/ca/floo/roadtrip/db/generated/tables/AvailabilityWatch.kt
```

- [ ] **Step 3: Verify sandbox PII coverage still holds**

`availability_watch` is already PII-classified (via `trigger_config`) and covered by a scrub in `scripts/sandbox_scrub.sql`; the new `app_user` FK is an additional PII reason, not a new uncovered table.

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.config.SandboxPrivateTablesTest'`
Expected: PASS (no change to the roots/scrub files needed).

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/resources/db/migration/V49__watch_owner.sql
git commit -m "feat(db): add owner_user_id to availability_watch (V49)"
```

---

## Task 3: Repo — persist and filter by owner

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepoTest.kt`

**Interfaces:**
- Consumes: `AVAILABILITY_WATCH.OWNER_USER_ID` (Task 2).
- Produces:
  - `AvailabilityWatchRepo.Watch.ownerUserId: Long`
  - `AvailabilityWatchRepo.CreateInput.ownerUserId: Long`
  - `list(status, poiId, campsiteId, ownerUserId: Long?, limit, offset)` and `count(status, poiId, campsiteId, ownerUserId: Long?)` — `ownerUserId == null` means "all owners" (admin).

- [ ] **Step 1: Write the failing test**

Add to `AvailabilityWatchRepoTest.kt` (use the file's existing seed/create helpers; if its `create` helper does not pass an owner, add an `ownerUserId` param):

```kotlin
@Test
fun `list scopes to owner and null owner returns all`() {
    val owner = seedAppUser(email = "owner@example.com")
    val other = seedAppUser(email = "other@example.com")
    val poiId = seedCatalogPoi(sourceId = "own-scope", name = "Scoped")

    val mine = repo.create(createInput(poiId, ownerUserId = owner.value))
    repo.create(createInput(poiId, ownerUserId = other.value))

    val ownScoped = repo.list(ownerUserId = owner.value)
    assertEquals(listOf(mine.id), ownScoped.map { it.id })
    assertEquals(owner.value, ownScoped.single().ownerUserId)

    val all = repo.list(ownerUserId = null)
    assertEquals(2, all.size)
    assertEquals(2, repo.count(ownerUserId = null))
    assertEquals(1, repo.count(ownerUserId = owner.value))
}
```

Add a `seedAppUser` helper if absent (mirrors `UserRepo.create`):

```kotlin
private fun seedAppUser(email: String): UserId =
    UserRepo(ctx).create(email = email, displayName = null, isEmailVerified = true).id
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityWatchRepoTest'`
Expected: FAIL — `ownerUserId` param/field unresolved.

- [ ] **Step 3: Add the field to `Watch` and map it**

In `AvailabilityWatchRepo.kt`, add to `data class Watch(...)`:

```kotlin
val ownerUserId: Long,
```

Map it in `fromRecord`:

```kotlin
ownerUserId = r.get(AVAILABILITY_WATCH.OWNER_USER_ID)!!,
```

- [ ] **Step 4: Add owner to `CreateInput` and write it in `create`**

Add to `data class CreateInput(...)`:

```kotlin
val ownerUserId: Long,
```

In `create(...)`, add to the insert chain (e.g. after `STOP_WHEN_TRIGGERED`):

```kotlin
.set(AVAILABILITY_WATCH.OWNER_USER_ID, input.ownerUserId)
```

- [ ] **Step 5: Thread `ownerUserId` through `scopeConditions`, `list`, `count`**

Change `scopeConditions` signature and body:

```kotlin
private fun scopeConditions(
    status: WatchStatus?,
    poiId: Long?,
    campsiteId: Long?,
    ownerUserId: Long?,
): org.jooq.Condition {
    val conds = mutableListOf<org.jooq.Condition>()
    if (status != null) conds += AVAILABILITY_WATCH.STATUS.eq(status.wireValue)
    if (ownerUserId != null) conds += AVAILABILITY_WATCH.OWNER_USER_ID.eq(ownerUserId)
    // ... existing poiId / campsiteId EXISTS subqueries unchanged ...
    return if (conds.isEmpty()) DSL.noCondition() else DSL.and(conds)
}
```

Add `ownerUserId: Long? = null` to `list(...)` and `count(...)` and forward it to `scopeConditions(status, poiId, campsiteId, ownerUserId)`.

- [ ] **Step 6: Run the test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.repo.AvailabilityWatchRepoTest'`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityWatchRepoTest.kt
git commit -m "feat(repo): persist and filter watches by owner_user_id"
```

---

## Task 4: Service — accept owner on create

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt`

**Interfaces:**
- Consumes: `AvailabilityWatchRepo.CreateInput.ownerUserId` (Task 3).
- Produces: `AvailabilityWatchService.create(ownerUserId: UserId, targets, campsiteFilters, startDate, endDate, cadenceSec, triggerKinds, triggerConfig, stopWhenTriggered): Watch`.

- [ ] **Step 1: Write the failing test**

Add to `AvailabilityWatchServiceTest.kt` (match the file's existing create-call style and `TargetInput` construction; seed a user for the FK):

```kotlin
@Test
fun `create stamps the owner`() {
    val owner = UserRepo(ctx).create(email = "svc-owner@example.com", displayName = null, isEmailVerified = true).id
    val poiId = seedCatalogPoi(sourceId = "svc-owner", name = "Owner")
    val watch =
        service.create(
            ownerUserId = owner,
            targets = listOf(AvailabilityWatchTargetRepo.TargetInput(poiId = poiId, campsiteId = null)),
            campsiteFilters = kotlinx.serialization.json.JsonObject(emptyMap()),
            startDate = java.time.LocalDate.parse("2026-07-04"),
            endDate = java.time.LocalDate.parse("2026-07-06"),
            cadenceSec = 60,
            triggerKinds = listOf("atc"),
            triggerConfig = kotlinx.serialization.json.JsonObject(emptyMap()),
            stopWhenTriggered = false,
        )
    assertEquals(owner.value, watch.ownerUserId)
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityWatchServiceTest'`
Expected: FAIL — `create` has no `ownerUserId` param.

- [ ] **Step 3: Add the parameter and pass it into `CreateInput`**

In `AvailabilityWatchService.create(...)`, add `ownerUserId: UserId` as the first parameter and set it on the `CreateInput`:

```kotlin
val input =
    AvailabilityWatchRepo.CreateInput(
        ownerUserId = ownerUserId.value,
        targets = targets,
        // ... rest unchanged ...
    )
```

Add `import ca.floo.roadtrip.model.domain.auth.UserId`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.service.availability.AvailabilityWatchServiceTest'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchService.kt backend/src/test/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchServiceTest.kt
git commit -m "feat(service): accept owner on watch create"
```

---

## Task 5: Controller — resolve the user, scope and enforce ownership

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchController.kt`
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt`
- Modify: `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt` (controller factory wiring; ownership behavior asserted via routes in Task 6 — the controller needs a real DB + repo, so there is no standalone controller test)

**Interfaces:**
- Consumes: `AvailabilityWatchRepo.list(..., ownerUserId)`, `AvailabilityWatchService.create(ownerUserId, ...)`, `Watch.ownerUserId`, domain `User.isAdmin`, `UserRepo.findById`.
- Produces: controller methods keyed on the caller —
  - `list(principal: Principal.User, status, poiId, campsiteId, limit, offset)`
  - `get(principal: Principal.User, id): AvailabilityWatchResponse?`
  - `create(principal: Principal.User, req)`
  - `update(principal: Principal.User, id, req)`
  - `delete(principal: Principal.User, id): Boolean`

- [ ] **Step 1: Add `UserRepo` dependency and a resolver**

Add `userRepo: UserRepo` to the `AvailabilityWatchController` constructor. Add imports `ca.floo.roadtrip.model.domain.auth.Principal`, `ca.floo.roadtrip.model.domain.auth.User`, `ca.floo.roadtrip.repo.UserRepo`. Add a private helper:

```kotlin
// Resolves the account for the calling principal. The route guard guarantees a
// Principal.User reached us; a missing app_user row would be a data bug, so
// failing loudly is correct.
private fun resolve(principal: Principal.User): User =
    requireNotNull(userRepo.findById(principal.userId)) {
        "no app_user for authenticated principal ${principal.userId}"
    }
```

- [ ] **Step 2: Scope `list`**

```kotlin
fun list(
    principal: Principal.User,
    status: WatchStatus?,
    poiId: Long?,
    campsiteId: Long?,
    limit: Int,
    offset: Int,
): AvailabilityWatchListResponse {
    val user = resolve(principal)
    val ownerFilter = if (user.isAdmin) null else user.id.value
    val rows = watchRepo.list(status, poiId, campsiteId, ownerFilter, limit, offset)
    val total = watchRepo.count(status, poiId, campsiteId, ownerFilter)
    return watchMapper.listResponse(rows, total, limit, offset)
}
```

- [ ] **Step 3: Enforce ownership on `get`**

```kotlin
fun get(principal: Principal.User, id: Long): AvailabilityWatchResponse? {
    val user = resolve(principal)
    val watch = watchRepo.findById(id) ?: return null
    if (!user.isAdmin && watch.ownerUserId != user.id.value) return null // 404, don't leak existence
    return watchMapper.response(watch, includeCapabilities = true)
}
```

- [ ] **Step 4: Stamp owner on `create`**

Thread `principal` in and pass `ownerUserId = resolve(principal).id` into `watchService.create(...)`:

```kotlin
fun create(
    principal: Principal.User,
    req: AvailabilityWatchCreateRequest,
): AvailabilityWatchControllerResult<AvailabilityWatchResponse> {
    val user = resolve(principal)
    // ... existing parse of req into `parsed` ...
    val watch =
        try {
            watchService.create(
                ownerUserId = user.id,
                targets = parsed.targets,
                // ... rest of the existing args unchanged ...
            )
        } catch (e: AvailabilityWatchValidationException) {
            return AvailabilityWatchControllerResult.Invalid(e.error, e.message)
        }
    return AvailabilityWatchControllerResult.Ok(watchMapper.response(watch))
}
```

- [ ] **Step 5: Enforce ownership on `update` and `delete`**

Add an ownership pre-check before mutating:

```kotlin
fun update(
    principal: Principal.User,
    id: Long,
    req: AvailabilityWatchUpdateRequest,
): AvailabilityWatchControllerResult<AvailabilityWatchResponse> {
    val user = resolve(principal)
    val existing = watchRepo.findById(id) ?: return AvailabilityWatchControllerResult.NotFound
    if (!user.isAdmin && existing.ownerUserId != user.id.value) return AvailabilityWatchControllerResult.NotFound
    // ... existing parse + watchService.update(...) unchanged ...
}

fun delete(principal: Principal.User, id: Long): Boolean {
    val user = resolve(principal)
    val existing = watchRepo.findById(id) ?: return false
    if (!user.isAdmin && existing.ownerUserId != user.id.value) return false
    return watchService.delete(id)
}
```

- [ ] **Step 6: Update DI wiring**

In `di/RouteModule.kt`, the `availabilityWatchController(...)` factory constructs `AvailabilityWatchController(...)`. Add `userRepo = UserRepo(ctx)` to that call (the module already imports `UserRepo` and has `ctx`).

- [ ] **Step 7: Update the test-side controller factory**

In `AvailabilityWatchRoutesTest.kt`, the private `availabilityWatchController(...)` builds the controller — add `userRepo = UserRepo(ctx)` there too (import `ca.floo.roadtrip.repo.UserRepo`). Compile-only here; behavior asserted in Task 6.

- [ ] **Step 8: Compile check**

Run: `./gradlew :backend:compileKotlin`
Expected: The route file still calls the old controller arity, so `AvailabilityWatchRoutes.kt` will not compile until Task 6. That is expected — Tasks 5 and 6 land together. Confirm the only compile errors are in `AvailabilityWatchRoutes.kt` (and its test), not in the controller/DI.

- [ ] **Step 9: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/service/availability/AvailabilityWatchController.kt backend/src/main/kotlin/ca/floo/roadtrip/di/RouteModule.kt backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt
git commit -m "feat(controller): scope watches to owner, 404 on cross-user access"
```

---

## Task 6: Routes — require a signed-in user, thread the principal

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/route/api/availability/AvailabilityWatchRoutes.kt`
- Test: `backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt`

**Interfaces:**
- Consumes: controller methods keyed on `Principal.User` (Task 5); `RouteAccess.User`; `ApplicationCall.principal()`.
- Produces: five endpoints returning 401 for anonymous callers and owner-scoped results for authed ones.

- [ ] **Step 1: Write the failing tests (anonymous → 401, owner scoping, cross-user 404)**

The existing route tests run anonymous. After the flip they must install `roadtripAuthorization` with a resolver and send a session cookie. Add shared helpers to `AvailabilityWatchRoutesTest.kt` (mirror `SettingsRoutesTest`):

```kotlin
private const val USER_TOKEN = "user-token"
private const val OTHER_TOKEN = "other-token"

private lateinit var ownerId: UserId
private lateinit var otherId: UserId

// Seeds two real users so owner FKs resolve; call at the start of each test body.
private fun seedUsers() {
    ownerId = UserRepo(ctx).create("owner@example.com", null, true).id
    otherId = UserRepo(ctx).create("other@example.com", null, true).id
}

private fun resolvePrincipalFor(token: String?): Principal =
    when (token) {
        USER_TOKEN -> Principal.User(ownerId, roles = emptySet())
        OTHER_TOKEN -> Principal.User(otherId, roles = emptySet())
        else -> Principal.Anonymous
    }

private fun HttpRequestBuilder.asUser(token: String = USER_TOKEN) {
    header(HttpHeaders.Cookie, "$SESSION_COOKIE=$token")
}

// Minimal valid create JSON for the seeded POI.
private fun createBody(poiId: Long): String =
    """{"poi_id": $poiId, "start_date": "2026-07-04", "end_date": "2026-07-06", "cadence_sec": 60, "trigger_kinds": ["atc"]}"""
```

Install the plugin in each test's `application { ... }` block (before `routeTestApplication { ... }`):

```kotlin
install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
```

New tests:

```kotlin
@Test
fun `GET watches anonymous returns 401`() =
    testApplication {
        application {
            install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
            routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
        }
        assertEquals(HttpStatusCode.Unauthorized, client.get(WATCHES_PATH).status)
    }

@Test
fun `GET watches lists only the caller's own`() =
    testApplication {
        application {
            install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
            routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
        }
        seedUsers()
        val poiId = seedPoi(sourceId = "mine", name = "Mine")
        client.post(WATCHES_PATH) { asUser(USER_TOKEN); contentType(ContentType.Application.Json); setBody(createBody(poiId)) }
        client.post(WATCHES_PATH) { asUser(OTHER_TOKEN); contentType(ContentType.Application.Json); setBody(createBody(poiId)) }
        val resp = client.get(WATCHES_PATH) { asUser(USER_TOKEN) }
        val watches = Json.parseToJsonElement(resp.bodyAsText()).jsonObject["watches"]!!.jsonArray
        assertEquals(1, watches.size)
    }

@Test
fun `POST delete of another user's watch returns 404`() =
    testApplication {
        application {
            install(roadtripAuthorization) { resolvePrincipal = ::resolvePrincipalFor }
            routeTestApplication { availabilityWatchRoutes(ctx, watchService()) }
        }
        seedUsers()
        val poiId = seedPoi(sourceId = "theirs", name = "Theirs")
        val created = client.post(WATCHES_PATH) { asUser(OTHER_TOKEN); contentType(ContentType.Application.Json); setBody(createBody(poiId)) }
        val id = Json.parseToJsonElement(created.bodyAsText()).jsonObject["watch"]!!.jsonObject["id"]!!.jsonPrimitive.long
        val resp = client.post(deleteWatchPath(id)) { asUser(USER_TOKEN) }
        assertEquals(HttpStatusCode.NotFound, resp.status)
    }
```

Update the file's existing create/list/modify/delete tests: install the plugin in their `application` blocks and add `asUser(USER_TOKEN)` to their requests (they otherwise now 401), and call `seedUsers()` at the top of any that create/list. Add imports: `Principal`, `UserId`, `UserRepo`, `roadtripAuthorization`, `SESSION_COOKIE`, `HttpHeaders`, `header`, `HttpRequestBuilder`, `install`.

- [ ] **Step 2: Run to verify the new anon test fails (routes not yet gated)**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.AvailabilityWatchRoutesTest'`
Expected: FAIL — anonymous GET returns 200 (and/or compile errors from the new controller arity).

- [ ] **Step 3: Flip access levels and thread the principal**

In `AvailabilityWatchRoutes.kt`, change every `.access(RouteAccess.Anonymous)` (5 sites) to `.access(RouteAccess.User)`. Add a helper mirroring `SettingsRoutes.requireUser`:

```kotlin
private suspend fun ApplicationCall.requireUser(): Principal.User? {
    val p = principal() as? Principal.User
    if (p == null) respondApiError("unauthenticated", HttpStatusCode.Unauthorized)
    return p
}
```

Then in each handler obtain the user and pass it to the controller, e.g. the GET list:

```kotlin
get {
    val user = call.requireUser() ?: return@get
    // ... existing parse of status/poiId/campsiteId/limit/offset ...
    call.respondJson(watches.list(user, status, poiId, campsiteId, limit, offset))
}.describeApi("watches", "List availability watches")
    .access(RouteAccess.User)
```

Apply the same `requireUser()` + pass-`user` change to `post` (create → `watches.create(user, req)`), `get /{id}` (→ `watches.get(user, id)`), `post /{id}/modify` (→ `watches.update(user, id, req)`), `post /{id}/delete` (→ `watches.delete(user, id)`). Add imports for `Principal`, `principal` (from `route.common`), and ensure `respondApiError` is imported.

- [ ] **Step 4: Run the full route test to verify pass**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.AvailabilityWatchRoutesTest'`
Expected: PASS (anon 401; owner scoping; cross-user 404; existing CRUD via `asUser`).

- [ ] **Step 5: Run the route-access coverage check**

Run: `./gradlew :backend:test --tests 'ca.floo.roadtrip.route.common.RouteAccessCoverageTest'`
Expected: PASS — all five watch routes now declare `RouteAccess.User`.

- [ ] **Step 6: Commit**

```bash
git add backend/src/main/kotlin/ca/floo/roadtrip/route/api/availability/AvailabilityWatchRoutes.kt backend/src/test/kotlin/ca/floo/roadtrip/route/AvailabilityWatchRoutesTest.kt
git commit -m "feat(routes): require signed-in user for /api/watches"
```

---

## Task 7: Frontend — auth-changed event module

**Files:**
- Create: `web/availability/auth-events.js`
- Test: `web/availability/auth-events.test.mjs`
- Modify: `web/topbar/auth.js`

**Interfaces:**
- Produces: `AUTH_CHANGED_EVENT = 'roadtrip:auth-changed'`, `notifyAuthChanged()`, `onAuthChanged(handler) -> unsubscribe`.

- [ ] **Step 1: Write the failing test**

Create `web/availability/auth-events.test.mjs`:

```javascript
import assert from 'node:assert/strict';
import test from 'node:test';

import { AUTH_CHANGED_EVENT, notifyAuthChanged, onAuthChanged } from './auth-events.js';

test('onAuthChanged receives notifyAuthChanged and unsubscribes', () => {
  const events = [];
  globalThis.window = new EventTarget(); // module uses window.*
  const off = onAuthChanged(() => events.push(1));
  notifyAuthChanged();
  assert.equal(events.length, 1);
  assert.equal(AUTH_CHANGED_EVENT, 'roadtrip:auth-changed');
  off();
  notifyAuthChanged();
  assert.equal(events.length, 1);
  delete globalThis.window;
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `node --test web/availability/auth-events.test.mjs`
Expected: FAIL — module not found.

- [ ] **Step 3: Create the module (mirrors watch-events.js)**

Create `web/availability/auth-events.js`:

```javascript
// web/availability/auth-events.js
//
// Decoupling seam between the auth row (topbar/auth.js), which knows when the
// caller signs in or out, and watch consumers (topbar/alerts.js, the watches
// page) that must re-fetch — watches are now per-user, so the visible set
// changes the instant identity changes. A window CustomEvent avoids an import
// cycle, matching watch-events.js.

export const AUTH_CHANGED_EVENT = 'roadtrip:auth-changed';

/** Fire after the auth row (re-)renders the caller's identity. */
export function notifyAuthChanged() {
  try {
    window.dispatchEvent(new CustomEvent(AUTH_CHANGED_EVENT));
  } catch {
    // Non-fatal: environments without CustomEvent just don't get live refresh.
  }
}

/** Subscribe to auth changes. Returns an unsubscribe function. */
export function onAuthChanged(handler) {
  window.addEventListener(AUTH_CHANGED_EVENT, handler);
  return () => window.removeEventListener(AUTH_CHANGED_EVENT, handler);
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `node --test web/availability/auth-events.test.mjs`
Expected: PASS.

- [ ] **Step 5: Fire the event from auth.js**

In `web/topbar/auth.js`, import `notifyAuthChanged` and call it at the end of `render(me)` (both signed-in and signed-out paths flow through `render`, so one call covers both):

```javascript
import { notifyAuthChanged } from '../availability/auth-events.js';
// ... at the very end of render(me), after setting rootEl.innerHTML / hidden:
notifyAuthChanged();
```

- [ ] **Step 6: Run the auth test to confirm no regression**

Run: `node --test web/topbar/auth.test.mjs`
Expected: PASS (the module's try/catch swallows a missing `window` in stubbed tests).

- [ ] **Step 7: Commit**

```bash
git add web/availability/auth-events.js web/availability/auth-events.test.mjs web/topbar/auth.js
git commit -m "feat(web): roadtrip:auth-changed event fired on auth render"
```

---

## Task 8: Frontend — hide the topbar alerts panel when signed out

**Files:**
- Modify: `web/topbar/alerts.js`
- Test: `web/topbar-alerts.test.mjs`

**Interfaces:**
- Consumes: `onAuthChanged` (Task 7); `HttpError.status` from `web/api/http.js` (surfaced by `listWatches`).
- Produces: exported pure helper `shouldHideAlerts(error) -> boolean`.

- [ ] **Step 1: Write the failing test**

Add to `web/topbar-alerts.test.mjs`:

```javascript
import { shouldHideAlerts } from './topbar/alerts.js';

test('shouldHideAlerts is true for a 401 and false otherwise', () => {
  assert.equal(shouldHideAlerts({ status: 401 }), true);
  assert.equal(shouldHideAlerts({ status: 500 }), false);
  assert.equal(shouldHideAlerts(null), false);
  assert.equal(shouldHideAlerts(new Error('network')), false);
});
```

- [ ] **Step 2: Run to verify it fails**

Run: `node --test web/topbar-alerts.test.mjs`
Expected: FAIL — `shouldHideAlerts` not exported.

- [ ] **Step 3: Implement the helper and use it in `refresh()`**

In `web/topbar/alerts.js` add near the top (after imports):

```javascript
import { onAuthChanged } from '../availability/auth-events.js';

const UNAUTHORIZED_STATUS = 401;

/** A 401 means "not signed in": hide the whole alerts panel. */
export function shouldHideAlerts(error) {
  return !!error && error.status === UNAUTHORIZED_STATUS;
}
```

Rework the `refresh()` catch so a 401 hides the panel; other errors keep degrading as today:

```javascript
async function refresh() {
  try {
    const [active, paused, done] = await Promise.all([
      listWatches({ status: 'active', limit: WATCH_LIST_LIMIT }),
      listWatches({ status: 'paused', limit: WATCH_LIST_LIMIT }),
      listWatches({ status: 'done', limit: WATCH_LIST_LIMIT }),
    ]);
    watches = [
      ...(active?.watches || []),
      ...(paused?.watches || []),
      ...(done?.watches || []),
    ].sort(byStartDate);
    await ensurePoiNames(watches);
    if (rootEl) rootEl.hidden = false;
    render();
  } catch (e) {
    if (shouldHideAlerts(e)) {
      watches = [];
      if (rootEl) { rootEl.hidden = true; rootEl.innerHTML = ''; }
      return;
    }
    console.warn('[alerts] watch fetch failed', e);
  }
}
```

In `initAlerts()`, subscribe to auth changes so the panel appears/disappears live: add `onAuthChanged(refresh);` next to the existing `onWatchesChanged(refresh);`.

- [ ] **Step 4: Run to verify it passes**

Run: `node --test web/topbar-alerts.test.mjs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/topbar/alerts.js web/topbar-alerts.test.mjs
git commit -m "feat(web): hide topbar alerts panel when signed out"
```

---

## Task 9: Frontend — watches page sign-in empty state

**Files:**
- Modify: `web/watches/watches-page.js`
- Test: `web/watches/watches-page.test.mjs` (create)

**Interfaces:**
- Consumes: `onAuthChanged` (Task 7); `HttpError.status`.
- Produces: exported pure helper `isUnauthorized(error) -> boolean`.

- [ ] **Step 1: Write the failing test**

Create `web/watches/watches-page.test.mjs`:

```javascript
import assert from 'node:assert/strict';
import test from 'node:test';

import { isUnauthorized } from './watches-page.js';

test('isUnauthorized detects a 401', () => {
  assert.equal(isUnauthorized({ status: 401 }), true);
  assert.equal(isUnauthorized({ status: 404 }), false);
  assert.equal(isUnauthorized(null), false);
});
```

Note: `watches-page.js` calls `init()` at module top level, which would boot the page (and crash on missing `document`) during import. Step 3 guards that call.

- [ ] **Step 2: Run to verify it fails**

Run: `node --test web/watches/watches-page.test.mjs`
Expected: FAIL — `isUnauthorized` not exported (or a `document`/`init` crash if the guard is not yet in place).

- [ ] **Step 3: Implement the guard, helper, and empty state**

In `web/watches/watches-page.js` add (after imports):

```javascript
import { onAuthChanged } from '../availability/auth-events.js';

const UNAUTHORIZED_STATUS = 401;

export function isUnauthorized(error) {
  return !!error && error.status === UNAUTHORIZED_STATUS;
}

function renderSignedOut() {
  const formHost = document.getElementById('form-host');
  const tableHost = document.getElementById('table-host');
  if (formHost) formHost.innerHTML = '';
  if (tableHost) {
    tableHost.innerHTML =
      '<p class="watches-signed-out">Sign in to create and manage your availability alerts.</p>';
  }
}
```

Wrap `loadWatches()`'s fetch in try/catch:

```javascript
async function loadWatches() {
  try {
    const [active, paused, done] = await Promise.all([
      listWatches({ status: 'active', limit: WATCH_LIST_LIMIT }),
      listWatches({ status: 'paused', limit: WATCH_LIST_LIMIT }),
      listWatches({ status: 'done', limit: WATCH_LIST_LIMIT }),
    ]);
    const watches = [
      ...(active?.watches || []),
      ...(paused?.watches || []),
      ...(done?.watches || []),
    ].sort(byStartDate);
    await ensurePoiNames(watches);
    tableCtrl.update({ watches, poiNames: poiNameCache });
  } catch (e) {
    if (isUnauthorized(e)) {
      renderSignedOut();
      return;
    }
    throw e;
  }
}
```

In `init()`, after the initial `await loadWatches()`, subscribe: `onAuthChanged(loadWatches);`. Replace the bare top-level `init();` with a guarded call:

```javascript
if (typeof document !== 'undefined' && document.getElementById('table-host')) init();
```

- [ ] **Step 4: Run to verify it passes**

Run: `node --test web/watches/watches-page.test.mjs`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add web/watches/watches-page.js web/watches/watches-page.test.mjs
git commit -m "feat(web): watches page shows sign-in empty state when signed out"
```

---

## Task 10: Full verification & PR

**Files:** none (verification only).

- [ ] **Step 1: Run the full backend test suite**

Run: `./gradlew :backend:test`
Expected: PASS. If it hangs locally, push with `SKIP_PREPUSH=1` and read the PR checks instead of loop-debugging the daemon.

- [ ] **Step 2: Run ktlint (CI gates it separately)**

Run: `./gradlew :backend:ktlintCheck`
Expected: PASS.

- [ ] **Step 3: Run all web tests**

Run: `node --test $(find web -name '*.test.mjs' | sort)`
Expected: PASS.

- [ ] **Step 4: Manual smoke via the run/verify skill**

Drive the app (`tilt up`): signed out → the topbar alerts panel is absent and `/watches` shows the sign-in prompt; sign in → the panel appears listing only this user's watches; create a watch, confirm it shows; a second account does not see it.

- [ ] **Step 5: Push and open a draft PR**

Write `pr_body.md` in the worktree (summary + test plan), then:

```bash
git push -u origin worktree-user-scoped-watches
gh pr create --draft --title "User-scoped availability watches" --body-file pr_body.md
```

Delete `pr_body.md` after the PR is created.

---

## Self-Review

**Spec coverage:**
- Owner column + delete legacy → Task 2. ✓
- Anonymous → 401 → Task 6. ✓
- Own-only + admin-all → Tasks 3 (repo filter), 5 (controller). ✓
- Domain `User` (not loose tuple), `Principal.User` stays thin → Task 1. ✓
- Poller untouched → confirmed (reuses `baseSelectFields`/`fromRecord`, no code change). ✓
- Both frontend surfaces → Tasks 8 (topbar), 9 (watches page). ✓
- Auth-change live refresh → Task 7 event, consumed in 8 & 9. ✓
- Cross-user access = 404 not 403 → Task 5 (`get`/`update`/`delete`). ✓

**Placeholder scan:** No TBD/TODO; every code step has concrete content. Where tests reuse file-local helpers (`seedPoi`, `createInput`, `TargetInput`), the plan says to match the file's existing style — acceptable since those helper names live in the file being edited.

**Type consistency:** `ownerUserId` is `Long` in the repo (`Watch`/`CreateInput`/filters) and `UserId` at the service/controller boundary (`.value` bridges them) — consistent throughout. Controller methods uniformly take `principal: Principal.User`. Event name `roadtrip:auth-changed` is identical in Tasks 7/8/9.
