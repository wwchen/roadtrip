# PR5: Force Pull — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "check now" route that force-pulls a poller: sets `next_run_at = now` so the scheduler picks it up on the next tick, and marks the resulting run as a forced fetch (the executor already always passes `force = true` to the provider per-call, per PR1 — this PR's "force" is about *scheduling* immediacy, not the provider request flag, which is already unconditional). Force pull still draws vendor tokens from the PR4 governor (no bypass) and is rate-limited per-poller by a cooldown so a user can't hammer "check now" into a governor-starving loop.

**Architecture:** A new `AvailabilityPollerRoutes` (or an addition to the existing `AvailabilityDashboardRoutes`/`AvailabilityRoutes`, whichever already owns poller-adjacent HTTP surface — confirm before creating a new route file) endpoint `POST /api/pollers/{id}/check-now` that: (1) loads the poller, (2) checks a per-poller cooldown (`last_force_pull_at` column, new in this PR), (3) if outside cooldown, sets `next_run_at = now` via a small `AvailabilityPollerRepo.forcePull` method and stamps `last_force_pull_at = now`, (4) returns 429-shaped DTO with `retryAfterSec` if still in cooldown, else 200 with the poller's new `nextRunAt`. The scheduler's normal claim loop picks the poller up on its next tick exactly as it would for any due poller — no special "force" code path in `Scheduler`/`AvailabilityPollExecutor.handle`, since PR1 already made every fetch `force = true` at the provider-call level (cache-busting), and PR4's governor already gates every fetch uniformly. Force pull's only job is to make `next_run_at` due immediately and to rate-limit how often a human can do that.

**Tech Stack:** Kotlin, Ktor (routes + DTOs), jOOQ, Postgres, Testcontainers/SharedDbTest.

## Global Constraints

- **Build needs JDK 17.** `export JAVA_HOME=$(/usr/libexec/java_home -v 17)` before any `./gradlew` from repo root.
- **jOOQ includes allowlist.** No new table — `availability_poller` gains one column (`last_force_pull_at`), already in the allowlist from PR1.
- **Layering.** Route parses the poller id, calls a service/repo method, sets the status code, returns a DTO. No SQL in the route file. Per project convention: `@Serializable` DTOs for the request/response body — do not hand-build JSON.
- **No inline magic constants.** The cooldown duration is a named `const val` default, overridable via config (same config surface PR4 uses for vendor buckets, if a natural per-poller-cooldown knob fits there — otherwise a new small config entry, still config-driven not hardcoded).
- **No leaky abstractions.** The route must not reach into `AvailabilityPollerRepo`'s SQL directly; it calls a named repo method (`forcePull`) that encapsulates the cooldown check + update as one atomic operation (avoids a check-then-act race between two concurrent "check now" clicks).
- **SharedDbTest pattern** for the new repo test; route test uses the project's existing Ktor test-host pattern (`testApplication { ... }`, matching how `AvailabilityWatchRoutes`/other routes are tested — check an existing route test file for the exact harness before writing a new one).
- **Postgres timestamp rounding.** Truncate to `ChronoUnit.MICROS` before comparing any Kotlin-constructed timestamp against one round-tripped through Postgres (same caveat as every prior PR in this sequence; PR1 hit this in `AvailabilityPollerMembershipTest`, commit `2bbccaac`).
- **Draws tokens, no bypass (spec).** Force pull does not skip PR4's governor. This PR does not modify the governor-gate code path in the executor at all — it only makes the poller *due*. If a forced poller happens to hit a starved bucket on its very next tick, PR4's existing starved branch reschedules it soon exactly as it would any other poller; force pull does not special-case that outcome.

## Cross-PR dependency (read before starting)

This plan assumes PR1 (V27–V28), PR2 (V29), PR3 (provisionally V30), and PR4 (provisionally V31) have landed in that order. **This plan's migration is provisionally V32.** None of PR1–PR4 are merged as of this writing — confirm the actual next-free `V*` at execution time; renumber if the chain reorders.

This plan also assumes PR4's `VendorRateLimiter`/governor-gate code path exists in the executor (so "force pull still draws tokens" is automatically true and requires no new code) — if PR4 has not landed when PR5 executes, the "draws tokens" behavior does not yet exist and this plan's Task 4 verification step should be adjusted to note the gap rather than assert it.

---

## File Structure

**New files:**
- `backend/src/main/resources/db/migration/V32__poller_force_pull_cooldown.sql` — `availability_poller.last_force_pull_at` column.
- `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepoForcePullTest.kt` (or added to the existing `AvailabilityPollerRepoTest.kt` — prefer extending the existing file so all poller-repo behavior stays in one place, matching how PR1 organized its own tests).
- `backend/src/test/kotlin/ca/floo/roadtrip/routes/AvailabilityPollerCheckNowRouteTest.kt` (or added to whichever existing route test file owns poller HTTP surface).

**Modified:**
- `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt` — add `forcePull(pollerId, now, cooldown): ForcePullResult`.
- `backend/src/main/kotlin/ca/floo/roadtrip/routes/AvailabilityDashboardRoutes.kt` (or `AvailabilityRoutes.kt` — **confirm which file currently owns `/api/pollers/*` GET/list surface before adding a POST here**; force-pull is poller-scoped HTTP, it belongs next to whichever file already serves poller reads) — add `POST /api/pollers/{id}/check-now`.
- `backend/src/main/kotlin/ca/floo/roadtrip/models/api/AvailabilityDashboardSchemas.kt` (or wherever poller-related DTOs live) — add `CheckNowResponseDto` / `CheckNowCooldownDto`.
- `web/components/availability/pollers-tab.js` (PR1 already renders a Pollers tab) — add a "Check now" button per poller row, calling the new route, matching the existing dashboard's fetch/render pattern.

---

## Interfaces (locked signatures used across tasks)

```kotlin
// AvailabilityPollerRepo.kt
sealed class ForcePullResult {
    data class Accepted(val nextRunAt: OffsetDateTime) : ForcePullResult()
    data class Cooldown(val retryAfterSec: Long) : ForcePullResult()
    object NotFound : ForcePullResult()
}

/** Atomically: if [pollerId] exists and is outside its cooldown window
 *  (last_force_pull_at IS NULL OR last_force_pull_at + cooldown <= now),
 *  sets next_run_at = now and last_force_pull_at = now in one UPDATE,
 *  returning Accepted. Otherwise returns Cooldown with the remaining wait,
 *  or NotFound. One round-trip, no check-then-act race between concurrent
 *  callers -- the WHERE clause embeds the cooldown check so only one
 *  concurrent call can win the UPDATE. */
fun forcePull(pollerId: Long, now: OffsetDateTime, cooldown: Duration): ForcePullResult
```

```kotlin
// models/api -- response DTOs (exact file TBD per Task 2 investigation)
@Serializable
data class CheckNowResponseDto(
    val pollerId: Long,
    val nextRunAt: String,   // ISO-8601
)

@Serializable
data class CheckNowCooldownDto(
    val pollerId: Long,
    val retryAfterSec: Long,
)
```

Route: `POST /api/pollers/{id}/check-now` → `200 CheckNowResponseDto` | `404` (poller not found) | `429 CheckNowCooldownDto` (cooldown active).

---

### Task 1: Schema migration — `last_force_pull_at`

**Files:**
- Create: `backend/src/main/resources/db/migration/V32__poller_force_pull_cooldown.sql`

- [ ] **Step 1: Write the migration.**

```sql
-- PR5: force pull. Tracks the last time a human forced this poller's
-- next_run_at forward, so the check-now route can enforce a per-poller
-- cooldown (a user mashing "check now" must not be able to starve the
-- vendor governor for everyone sharing this poller).
ALTER TABLE availability_poller ADD COLUMN last_force_pull_at TIMESTAMPTZ;
```

- [ ] **Step 2: Regenerate jOOQ + confirm compile.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:generateJooq :backend:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit.**

```
git add backend/src/main/resources/db/migration/V32__poller_force_pull_cooldown.sql
git commit -m "feat(force-pull): V32 migration -- availability_poller.last_force_pull_at" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `AvailabilityPollerRepo.forcePull`

**Files:**
- Modify: `backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt`
- Modify (or create): `backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepoTest.kt`

**Interfaces:** `forcePull(pollerId, now, cooldown): ForcePullResult`, per the block above.

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test fun `forcePull sets next_run_at to now and stamps last_force_pull_at when outside cooldown`() {
    val repo = AvailabilityPollerRepo(ctx)
    val poi = insertPoi()
    val pollerId = repo.upsertActive("recgov", "A", poi, pullNextRunAt = OffsetDateTime.now().plusHours(1))
    val now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)
    val result = repo.forcePull(pollerId, now, cooldown = Duration.ofSeconds(30))
    assertTrue(result is AvailabilityPollerRepo.ForcePullResult.Accepted)
    assertEquals(now, repo.findById(pollerId)!!.nextRunAt)
}

@Test fun `forcePull rejects a second call inside the cooldown window`() {
    val repo = AvailabilityPollerRepo(ctx)
    val poi = insertPoi()
    val pollerId = repo.upsertActive("recgov", "A", poi, null)
    val now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)
    repo.forcePull(pollerId, now, cooldown = Duration.ofSeconds(30))
    val second = repo.forcePull(pollerId, now.plusSeconds(5), cooldown = Duration.ofSeconds(30))
    assertTrue(second is AvailabilityPollerRepo.ForcePullResult.Cooldown)
    val remaining = (second as AvailabilityPollerRepo.ForcePullResult.Cooldown).retryAfterSec
    assertEquals(25L, remaining)  // 30s cooldown - 5s elapsed
}

@Test fun `forcePull succeeds again once the cooldown has elapsed`() {
    val repo = AvailabilityPollerRepo(ctx)
    val poi = insertPoi()
    val pollerId = repo.upsertActive("recgov", "A", poi, null)
    val now = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS)
    repo.forcePull(pollerId, now, cooldown = Duration.ofSeconds(30))
    val later = repo.forcePull(pollerId, now.plusSeconds(31), cooldown = Duration.ofSeconds(30))
    assertTrue(later is AvailabilityPollerRepo.ForcePullResult.Accepted)
}

@Test fun `forcePull on an unknown poller id returns NotFound`() {
    val repo = AvailabilityPollerRepo(ctx)
    val result = repo.forcePull(pollerId = 999_999L, now = OffsetDateTime.now(), cooldown = Duration.ofSeconds(30))
    assertEquals(AvailabilityPollerRepo.ForcePullResult.NotFound, result)
}

@Test fun `concurrent force-pull calls inside the cooldown only one wins`() {
    // Two threads/coroutines call forcePull at effectively the same "now" against
    // a poller with no prior last_force_pull_at. Exactly one should get Accepted;
    // the other Cooldown -- proves the WHERE-clause-embedded check prevents a
    // check-then-act race, not just a logical race in single-threaded tests.
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*AvailabilityPollerRepoTest*'`
Expected: FAIL — `forcePull` unresolved.

- [ ] **Step 3: Implement.**

```kotlin
sealed class ForcePullResult {
    data class Accepted(val nextRunAt: OffsetDateTime) : ForcePullResult()
    data class Cooldown(val retryAfterSec: Long) : ForcePullResult()
    object NotFound : ForcePullResult()
}

fun forcePull(pollerId: Long, now: OffsetDateTime, cooldown: Duration): ForcePullResult {
    val updated = ctx
        .update(AVAILABILITY_POLLER)
        .set(AVAILABILITY_POLLER.NEXT_RUN_AT, now)
        .set(AVAILABILITY_POLLER.LAST_FORCE_PULL_AT, now)
        .set(AVAILABILITY_POLLER.UPDATED_AT, now)
        .where(AVAILABILITY_POLLER.ID.eq(pollerId))
        .and(
            AVAILABILITY_POLLER.LAST_FORCE_PULL_AT.isNull
                .or(AVAILABILITY_POLLER.LAST_FORCE_PULL_AT.plus(cooldown).le(now)),
        ).execute()
    if (updated > 0) return ForcePullResult.Accepted(nextRunAt = now)

    // The UPDATE's WHERE didn't match -- either the poller doesn't exist, or it
    // exists but is still in cooldown. Disambiguate with a read (outside the
    // race window that mattered -- the WHERE clause above already made the
    // accept/reject decision atomically; this read is just for the error shape).
    val row = findById(pollerId) ?: return ForcePullResult.NotFound
    val cooldownEndsAt = row.lastRunAt?.let { it } // placeholder -- use row's last_force_pull_at once exposed on Poller
    val lastForcePullAt = ctx
        .select(AVAILABILITY_POLLER.LAST_FORCE_PULL_AT)
        .from(AVAILABILITY_POLLER)
        .where(AVAILABILITY_POLLER.ID.eq(pollerId))
        .fetchOne(AVAILABILITY_POLLER.LAST_FORCE_PULL_AT)!!
    val retryAfterSec = Duration.between(now, lastForcePullAt.plus(cooldown)).seconds.coerceAtLeast(0)
    return ForcePullResult.Cooldown(retryAfterSec)
}
```

Also add `lastForcePullAt: OffsetDateTime?` to the `Poller` data class and its `fromRecord` mapping (cleaner than the placeholder inline `select` above — do this first, then simplify `forcePull`'s disambiguation branch to `findById(pollerId)!!.lastForcePullAt` directly instead of a second raw query).

- [ ] **Step 4: Run to verify it passes; then full suite.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test`

- [ ] **Step 5: Commit.**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepo.kt backend/src/test/kotlin/ca/floo/roadtrip/repo/AvailabilityPollerRepoTest.kt
git commit -m "feat(force-pull): AvailabilityPollerRepo.forcePull with atomic cooldown check" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `POST /api/pollers/{id}/check-now` route

**Files:**
- Investigate first, then modify: whichever of `AvailabilityDashboardRoutes.kt` / `AvailabilityRoutes.kt` currently serves poller HTTP reads (`GET /api/pollers`, `GET /api/pollers/{id}`, per PR1's dashboard rework) — add the POST there.
- Modify: the DTO file that already holds poller-related response shapes.
- Test: the existing route test file for that router (extend, following its established `testApplication { ... }` harness).

**Interfaces:** DTOs + route per the Interfaces block above.

- [ ] **Step 1: Write the failing test.**

```kotlin
@Test fun `check-now returns 200 with the new next_run_at when outside cooldown`() = testApplication {
    // Arrange: seed a poller via the repo directly (matching the file's existing
    // seeding pattern), call POST /api/pollers/{id}/check-now, assert 200 +
    // CheckNowResponseDto.nextRunAt is ~now.
}

@Test fun `check-now returns 429 with retryAfterSec when inside cooldown`() = testApplication {
    // Call check-now twice back to back; second call asserts 429 + CheckNowCooldownDto.
}

@Test fun `check-now on an unknown poller id returns 404`() = testApplication {
}
```

- [ ] **Step 2: Run to verify it fails.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test --tests '*<the route test class>*'`

- [ ] **Step 3: Implement the route.**

```kotlin
post("/api/pollers/{id}/check-now") {
    val id = call.parameters["id"]?.toLongOrNull()
        ?: return@post call.respond(HttpStatusCode.BadRequest)
    when (val result = pollers.forcePull(id, OffsetDateTime.now(), cooldown = FORCE_PULL_COOLDOWN)) {
        is AvailabilityPollerRepo.ForcePullResult.Accepted ->
            call.respond(HttpStatusCode.OK, CheckNowResponseDto(pollerId = id, nextRunAt = result.nextRunAt.toString()))
        is AvailabilityPollerRepo.ForcePullResult.Cooldown ->
            call.respond(HttpStatusCode.TooManyRequests, CheckNowCooldownDto(pollerId = id, retryAfterSec = result.retryAfterSec))
        AvailabilityPollerRepo.ForcePullResult.NotFound ->
            call.respond(HttpStatusCode.NotFound)
    }
}
```

`FORCE_PULL_COOLDOWN` = a named `const val Duration` (e.g. 30s default) at the top of the route file, or sourced from the same config surface PR4 introduced if a per-poller-cooldown knob naturally belongs there — check PR4's `VendorRateLimitConfig`/config file structure before deciding; if force-pull cooldown is conceptually a scheduling knob rather than a vendor-rate knob, a separate small config entry is fine (do not force it into `VendorRateLimitConfig` just because that's the newest config surface).

- [ ] **Step 4: Run to verify it passes; then full suite + ktlint.**

Run: `export JAVA_HOME=$(/usr/libexec/java_home -v 17); ./gradlew :backend:test`
Then: `./gradlew :backend:ktlintCheck`

- [ ] **Step 5: Commit.**

```
git add backend/src/main/kotlin/ca/floo/roadtrip/routes backend/src/main/kotlin/ca/floo/roadtrip/models/api backend/src/test/kotlin/ca/floo/roadtrip/routes
git commit -m "feat(force-pull): POST /api/pollers/{id}/check-now route with cooldown" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Dashboard "Check now" button

**Files:**
- Modify: `web/components/availability/pollers-tab.js` (PR1's Pollers tab already renders one row per poller with attached-watch count — add an action button per row).
- Modify: `web/api/availability-dashboard-api.js` (or wherever the dashboard's fetch wrappers live) — add a `checkNowPoller(pollerId)` call.

**Interfaces:** a button per poller row; on click, `POST` the new route, show the 200/429/404 outcome inline (toast or inline text — match whatever feedback pattern the dashboard already uses elsewhere, e.g. for pause/resume actions if those exist).

- [ ] **Step 1: Add the API wrapper.** Mirror the existing fetch-wrapper pattern in `web/api/availability-dashboard-api.js` (check how e.g. the existing poller list call is structured) for `checkNowPoller(pollerId)`.

- [ ] **Step 2: Add the button + handler in `pollers-tab.js`.** On 200: show new `next_run_at` (or just a success flash + row refresh). On 429: show `retryAfterSec` (e.g. "try again in 18s"). On 404: shouldn't normally happen from a rendered row, but handle gracefully (row likely stale — trigger a refresh).

- [ ] **Step 3: Manual verification** (no headless test harness for this project's vanilla-JS dashboard per its existing structure — confirm by running the dev stack and clicking through, per Task 5 below, rather than writing a JS unit test if none of the sibling components have one).

- [ ] **Step 4: Commit.**

```
git add web/components/availability/pollers-tab.js web/api/availability-dashboard-api.js
git commit -m "feat(force-pull): Check now button on the Pollers dashboard tab" -m "Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: End-to-end verification

**Files:** none (verification only). Uses the Tilt dev stack.

- [ ] **Step 1: Bring up the stack.** `tilt up`.

- [ ] **Step 2: Force-pull a poller whose `next_run_at` is far in the future.** Click "Check now" (or `curl -X POST localhost:.../api/pollers/{id}/check-now`). Confirm via SQL:

Run: `SELECT id, next_run_at, last_force_pull_at FROM availability_poller WHERE id = <id>;`
Expected: `next_run_at` ≈ now, `last_force_pull_at` ≈ now.

- [ ] **Step 3: Confirm the scheduler picks it up on the next tick** (within `tickInterval`, default 5s per `Scheduler`) — a new `availability_run` row appears for that poller.

- [ ] **Step 4: Confirm the cooldown rejects a rapid second click.** Click "Check now" again immediately; expect a 429/inline cooldown message, and confirm `next_run_at` did NOT move a second time.

- [ ] **Step 5: Confirm force pull still draws governor tokens (PR4).** With a deliberately tiny vendor bucket configured, force-pull several pollers on the same vendor back to back (past the cooldown, using different poller ids to avoid the per-poller cooldown); confirm the ones beyond the bucket's capacity show the governor-starved reschedule behavior (no run row, `next_run_at` pulled to `GOVERNOR_STARVED_RETRY_SEC` from PR4), proving force pull did not bypass the governor.

- [ ] **Step 6: Record the evidence** (SQL results, screenshot of the cooldown message) in the PR description.

---

## Self-Review

**Spec coverage (PR5 bullet: "a 'check now' route that sets a poller's next_run_at=now and force-fetches (executor already passes force=true), with a per-poller cooldown. Draws tokens."):**
- "check now" route sets `next_run_at = now` → Task 2 + Task 3. ✓
- force-fetches — no new code needed; PR1's `force = true` on every `CatalogAvailabilityRequest` already makes every fetch a forced (cache-busting) fetch, and this plan's Architecture section explicitly documents why no executor change is required here. ✓
- per-poller cooldown → Task 2 (`forcePull`'s atomic WHERE-embedded check) + Task 3 (429 response) + Task 5 verification. ✓
- draws tokens (no governor bypass) → explicitly not modified in this plan (the executor's PR4 governor gate runs unconditionally regardless of why the poller became due); verified in Task 5 Step 5 rather than asserted by new code, since there is nothing force-pull-specific to gate. ✓

**Deliberately out of PR5 scope (documented):** PR6 alerts.

**PR6 (out of scope):** Alert firing/notification evaluation over the cube is deferred to PR6; force pull is a scheduling primitive a future alert-adjacent "check now, then evaluate triggers immediately" UX could build on, but that composition is not built here.

**Open risks flagged during planning:**
1. **Migration numbering is provisional (V32).** Depends on PR1 (V27/V28), PR2 (V29), PR3 (V30, provisional), PR4 (V31, provisional) landing first in order. Confirm at execution time; this is the last (and therefore most exposed to drift) migration number in the chain.
2. **Route file ownership unconfirmed.** Task 3 explicitly defers to "investigate first" which existing route file owns poller HTTP surface post-PR1's dashboard rework — this plan does not assume a filename because that surface may itself have shifted across PR1/PR2/PR3/PR4's dashboard changes.
3. **Cooldown default value and its config surface.** The plan uses 30s as an illustrative default (named constant, not hardcoded at the call site) but does not mandate a specific number from the spec (the spec says "a per-poller cooldown" without a value) — flagged as a product decision, not an engineering one; confirm with whoever owns the dashboard UX before locking the default.
4. **No JS test harness assumed for the dashboard button.** Task 4 explicitly checks whether sibling dashboard components have any JS test coverage before deciding whether to add one for the new button — if the project has since added a JS test pattern (none was found in PR1's file list at the time this plan was written), that convention should be followed instead of skipping to manual verification.
5. **`forcePull`'s NotFound-vs-Cooldown disambiguation branch** in Task 2's sketch does one extra read after a failed UPDATE; the plan explicitly calls out simplifying this once `lastForcePullAt` is exposed on the `Poller` type, rather than shipping the placeholder double-query version.
